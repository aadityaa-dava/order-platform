package dev.aadityaa.orderplatform.platform.messaging;

import java.util.UUID;

/** One row of the outbox. traceContext is the W3C traceparent of the span that wrote it (may be null). */
public record OutboxRecord(long id, UUID eventId, UUID aggregateId, String eventType, String topic, String payload,
                           String traceContext) {
}
