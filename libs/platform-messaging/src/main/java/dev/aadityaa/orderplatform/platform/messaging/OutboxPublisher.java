package dev.aadityaa.orderplatform.platform.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.propagation.Propagator;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Polling publisher for the transactional outbox (ADR-0002).
 * Delivery is at-least-once: a crash after the broker ack but before the commit re-sends the event.
 * Each event is sent inside an "outbox publish" span whose parent is the trace that wrote the event,
 * so the saga stays one trace even though publishing happens later, on another thread.
 */
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final OutboxProperties props;
    private final Counter publishedCounter;
    private final Counter failureCounter;
    private final Tracer tracer;           // null when tracing is not configured
    private final Propagator propagator;   // null when tracing is not configured

    public OutboxPublisher(OutboxRepository outbox, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                           OutboxProperties props, MeterRegistry registry, Tracer tracer, Propagator propagator) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.tx = tx;
        this.props = props;
        this.tracer = tracer;
        this.propagator = propagator;
        this.publishedCounter = Counter.builder("outbox.published").description("Events published to Kafka").register(registry);
        this.failureCounter = Counter.builder("outbox.publish.failures")
                .description("Failed attempts to publish an outbox event").register(registry);
    }

    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:200}")
    public void poll() {
        try {
            int published;
            do {
                Integer result = tx.execute(status -> publishBatch());
                published = result == null ? 0 : result;
            } while (published == props.batchSize()); // full batch: more may be waiting, keep draining
        } catch (Exception e) {
            failureCounter.increment();
            log.warn("Outbox publish cycle failed; will retry on next poll: {}", e.toString());
        }
    }

    @Scheduled(fixedDelayString = "${outbox.cleanup-interval-ms:60000}")
    public void cleanup() {
        try {
            int deleted = outbox.deletePublishedOlderThanSeconds(props.cleanupRetentionSeconds());
            if (deleted > 0) {
                log.info("Outbox cleanup removed {} published rows", deleted);
            }
        } catch (Exception e) {
            log.warn("Outbox cleanup failed: {}", e.toString());
        }
    }

    private int publishBatch() {
        List<OutboxRecord> batch = outbox.lockUnpublished(props.batchSize());
        if (batch.isEmpty()) {
            return 0;
        }
        List<CompletableFuture<?>> futures = new ArrayList<>(batch.size());
        for (OutboxRecord record : batch) {
            futures.add(send(record));
        }

        List<Long> published = new ArrayList<>(batch.size());
        for (int i = 0; i < batch.size(); i++) {
            try {
                futures.get(i).get(props.sendTimeoutMs(), TimeUnit.MILLISECONDS);
                published.add(batch.get(i).id());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                failureCounter.increment();
                log.warn("Publish failed for outbox id {}; stopping this batch: {}", batch.get(i).id(), e.toString());
                break; // only the leading successes are marked; the rest stay unpublished
            }
        }
        outbox.markPublished(published);
        publishedCounter.increment(published.size());
        return published.size();
    }

    private CompletableFuture<?> send(OutboxRecord record) {
        Span relay = startRelaySpan(record);
        try (Tracer.SpanInScope ignored = relay == null ? null : tracer.withSpan(relay)) {
            var message = new ProducerRecord<String, String>(record.topic(), record.aggregateId().toString(), record.payload());
            message.headers().add("eventId", record.eventId().toString().getBytes(StandardCharsets.UTF_8));
            message.headers().add("eventType", record.eventType().getBytes(StandardCharsets.UTF_8));
            return kafka.send(message); // the Kafka send span becomes a child of the relay span
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        } finally {
            if (relay != null) {
                relay.end();
            }
        }
    }

    /** Continues the trace that wrote the event: its parent is the span saved in the outbox row. */
    private Span startRelaySpan(OutboxRecord record) {
        if (tracer == null || propagator == null || record.traceContext() == null) {
            return null;
        }
        try {
            return propagator.extract(Map.of("traceparent", record.traceContext()), Map::get)
                    .name("outbox publish")
                    .tag("outbox.event_type", record.eventType())
                    .start();
        } catch (Exception e) {
            return null; // tracing must never stop an event from being published
        }
    }
}
