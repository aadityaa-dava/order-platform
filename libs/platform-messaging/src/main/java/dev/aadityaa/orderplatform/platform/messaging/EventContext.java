package dev.aadityaa.orderplatform.platform.messaging;

import java.util.UUID;

/** Envelope fields every event handler needs. */
public record EventContext(UUID eventId, UUID orderId, UUID correlationId) {
}
