package dev.aadityaa.orderplatform.platform.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.propagation.Propagator;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Builds the standard envelope and writes it to the outbox, together with the current trace context so the
 * publisher can later continue the same trace. Must be called inside the transaction that changed the state.
 */
public class OutboxWriter {

    private final OutboxRepository outbox;
    private final ObjectMapper mapper;
    private final String producer;
    private final MeterRegistry registry;
    private final Tracer tracer;           // null when tracing is not configured
    private final Propagator propagator;   // null when tracing is not configured

    public OutboxWriter(OutboxRepository outbox, ObjectMapper mapper, String producer, MeterRegistry registry,
                        Tracer tracer, Propagator propagator) {
        this.outbox = outbox;
        this.mapper = mapper;
        this.producer = producer;
        this.registry = registry;
        this.tracer = tracer;
        this.propagator = propagator;
    }

    public <T> UUID write(String topic, UUID orderId, UUID correlationId, String eventType, int eventVersion, T payload) {
        var envelope = new EventEnvelope<>(UUID.randomUUID(), eventType, eventVersion, Instant.now(),
                orderId, correlationId, producer, payload);
        try {
            outbox.insert(envelope.eventId(), orderId, eventType, topic, mapper.writeValueAsString(envelope),
                    currentTraceparent());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize " + eventType, e);
        }
        countAfterCommit(eventType);
        return envelope.eventId();
    }

    /** The W3C traceparent of the span that is active now (the HTTP request or the Kafka message being handled). */
    private String currentTraceparent() {
        if (tracer == null || propagator == null) {
            return null;
        }
        Span span = tracer.currentSpan();
        if (span == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(span.context(), carrier, Map::put);
        return carrier.get("traceparent");
    }

    /** Counts only events whose transaction committed, so a rollback does not inflate the number. */
    private void countAfterCommit(String eventType) {
        Runnable count = () -> Counter.builder("outbox.events.written")
                .description("Events written to the outbox, by type").tag("eventType", eventType)
                .register(registry).increment();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    count.run();
                }
            });
        } else {
            count.run();
        }
    }
}
