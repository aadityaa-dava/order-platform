package dev.aadityaa.orderplatform.contracts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Validates events against the shared JSON Schemas and provides a valid example of each event type. */
public final class EventContracts {

    public static final List<String> EVENT_TYPES = List.of(
            "OrderCreated", "OrderConfirmed", "OrderRejected", "OrderCancelled",
            "StockReserved", "StockReservationFailed", "StockReleased",
            "PaymentCompleted", "PaymentFailed");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private static final Map<String, JsonSchema> SCHEMAS = new ConcurrentHashMap<>();

    private EventContracts() {
    }

    /** @return human-readable violations; an empty list means the event honours the contract. */
    public static List<String> validate(String eventType, String eventJson) {
        JsonNode node;
        try {
            node = MAPPER.readTree(eventJson);
        } catch (JsonProcessingException e) {
            return List.of("Not valid JSON: " + e.getOriginalMessage());
        }
        return schema(eventType).validate(node).stream().map(ValidationMessage::getMessage).sorted().toList();
    }

    public static String example(String eventType) {
        return read("/contracts/examples/" + eventType + ".json");
    }

    private static JsonSchema schema(String eventType) {
        return SCHEMAS.computeIfAbsent(eventType,
                type -> FACTORY.getSchema(read("/contracts/schemas/" + type + ".schema.json")));
    }

    private static String read(String resource) {
        try (InputStream in = EventContracts.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("No such contract resource: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + resource, e);
        }
    }
}
