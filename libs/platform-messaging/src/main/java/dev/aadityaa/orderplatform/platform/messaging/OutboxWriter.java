package dev.aadityaa.orderplatform.platform.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Builds the standard envelope and writes it to the outbox.
 * Must be called inside the transaction that changed the business state.
 */
public class OutboxWriter {

    private final OutboxRepository outbox;
    private final ObjectMapper mapper;
    private final String producer;
    private final MeterRegistry registry;

    public OutboxWriter(OutboxRepository outbox, ObjectMapper mapper, String producer, MeterRegistry registry) {
        this.outbox = outbox;
        this.mapper = mapper;
        this.producer = producer;
        this.registry = registry;
    }

    public <T> UUID write(String topic, UUID orderId, UUID correlationId, String eventType, int eventVersion, T payload) {
        var envelope = new EventEnvelope<>(UUID.randomUUID(), eventType, eventVersion, Instant.now(),
                orderId, correlationId, producer, payload);
        try {
            outbox.insert(envelope.eventId(), orderId, eventType, topic, mapper.writeValueAsString(envelope));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize " + eventType, e);
        }
        countAfterCommit(eventType);
        return envelope.eventId();
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
