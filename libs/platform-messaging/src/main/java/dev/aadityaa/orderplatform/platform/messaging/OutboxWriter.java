package dev.aadityaa.orderplatform.platform.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;

/**
 * Builds the standard envelope and writes it to the outbox.
 * Must be called inside the transaction that changed the business state.
 */
public class OutboxWriter {

    private final OutboxRepository outbox;
    private final ObjectMapper mapper;
    private final String producer;

    public OutboxWriter(OutboxRepository outbox, ObjectMapper mapper, String producer) {
        this.outbox = outbox;
        this.mapper = mapper;
        this.producer = producer;
    }

    public <T> UUID write(String topic, UUID orderId, UUID correlationId, String eventType, int eventVersion, T payload) {
        var envelope = new EventEnvelope<>(UUID.randomUUID(), eventType, eventVersion, Instant.now(),
                orderId, correlationId, producer, payload);
        try {
            outbox.insert(envelope.eventId(), orderId, eventType, topic, mapper.writeValueAsString(envelope));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize " + eventType, e);
        }
        return envelope.eventId();
    }
}
