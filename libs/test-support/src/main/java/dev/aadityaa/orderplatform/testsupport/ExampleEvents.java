package dev.aadityaa.orderplatform.testsupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.aadityaa.orderplatform.contracts.EventContracts;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Builds test events from the contract examples, with fresh ids, and refuses to build one that breaks the contract. */
public final class ExampleEvents {

    public record Sample(UUID eventId, UUID orderId, String json) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ExampleEvents() {
    }

    public static Sample of(String eventType, Consumer<ObjectNode> payloadEditor) {
        return of(eventType, UUID.randomUUID(), payloadEditor);
    }

    public static Sample of(String eventType, UUID orderId, Consumer<ObjectNode> payloadEditor) {
        try {
            ObjectNode event = (ObjectNode) MAPPER.readTree(EventContracts.example(eventType));
            UUID eventId = UUID.randomUUID();
            event.put("eventId", eventId.toString());
            event.put("orderId", orderId.toString());
            event.put("correlationId", UUID.randomUUID().toString());
            payloadEditor.accept((ObjectNode) event.get("payload"));
            String json = MAPPER.writeValueAsString(event);

            List<String> violations = EventContracts.validate(eventType, json);
            if (!violations.isEmpty()) {
                throw new IllegalStateException("Test built a " + eventType + " that breaks its contract: " + violations);
            }
            return new Sample(eventId, orderId, json);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
