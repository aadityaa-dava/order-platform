package dev.aadityaa.orderplatform.order.outbox;

import java.util.UUID;

public record OutboxRecord(long id, UUID eventId, UUID aggregateId, String eventType, String topic, String payload) {
}
