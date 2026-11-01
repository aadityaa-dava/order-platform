package dev.aadityaa.orderplatform.platform.messaging;

import java.time.Instant;
import java.util.UUID;

/** Common wrapper for every event; matches docs/events.md. */
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        UUID orderId,
        UUID correlationId,
        String producer,
        T payload) {
}
