package dev.aadityaa.orderplatform.platform.messaging;

import java.util.UUID;

public record OutboxRecord(long id, UUID eventId, UUID aggregateId, String eventType, String topic, String payload) {
}
