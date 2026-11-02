package dev.aadityaa.orderplatform.platform.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;

/**
 * Puts the correlation id, order id, event id and event type of the record being processed into the
 * logging context, so every log line written while handling it (including retry and dead-letter lines
 * from the error handler) can be traced back to one order.
 */
public class LogContextRecordInterceptor implements RecordInterceptor<Object, Object> {

    static final List<String> KEYS = List.of("correlationId", "orderId", "eventId", "eventType");

    private final ObjectMapper mapper;

    public LogContextRecordInterceptor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        if (record.key() != null) {
            MDC.put("orderId", String.valueOf(record.key())); // the key is the order id, even for an unparseable message
        }
        try {
            JsonNode root = mapper.readTree(String.valueOf(record.value()));
            for (String key : KEYS) {
                JsonNode node = root.get(key);
                if (node != null && node.isTextual()) {
                    MDC.put(key, node.asText());
                }
            }
        } catch (Exception ignored) {
            // a poison message: the error handler deals with it, and the record key is already in the context
        }
        return record;
    }

    /** Called after the listener, and after the error handler if the listener failed. */
    @Override
    public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        KEYS.forEach(MDC::remove);
    }
}
