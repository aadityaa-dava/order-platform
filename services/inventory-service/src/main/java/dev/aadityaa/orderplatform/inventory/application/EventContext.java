package dev.aadityaa.orderplatform.inventory.application;

import java.util.UUID;

/** Envelope fields every handler needs, extracted once by the listener. */
public record EventContext(UUID eventId, UUID orderId, UUID correlationId) {
}
