package dev.aadityaa.orderplatform.platform.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;

/** A received event: parsed envelope plus the raw payload, which each service maps to its own type. */
public record IncomingEvent(EventContext context, String eventType, JsonNode payload) {

    public static IncomingEvent parse(ObjectMapper mapper, String json) throws JsonProcessingException {
        JsonNode root = mapper.readTree(json);
        var context = new EventContext(uuid(root, "eventId"), uuid(root, "orderId"), uuid(root, "correlationId"));
        return new IncomingEvent(context, root.path("eventType").asText(""), root.path("payload"));
    }

    /** Tolerant reader: fields the target type does not declare are ignored. */
    public <T> T payloadAs(ObjectMapper mapper, Class<T> type) throws JsonProcessingException {
        return mapper.treeToValue(payload, type);
    }

    private static UUID uuid(JsonNode root, String field) {
        String value = root.path(field).asText("");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Event is missing required field '" + field + "'");
        }
        return UUID.fromString(value);
    }
}
