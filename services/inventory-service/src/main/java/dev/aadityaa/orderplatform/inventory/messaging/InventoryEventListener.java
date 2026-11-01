package dev.aadityaa.orderplatform.inventory.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aadityaa.orderplatform.inventory.application.EventContext;
import dev.aadityaa.orderplatform.inventory.application.ReservationService;
import dev.aadityaa.orderplatform.inventory.events.InboundEvents;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class InventoryEventListener {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventListener.class);

    private final ReservationService service;
    private final ObjectMapper mapper;

    public InventoryEventListener(ReservationService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @KafkaListener(topics = InboundEvents.ORDER_TOPIC)
    public void onOrderEvent(ConsumerRecord<String, String> record) throws JsonProcessingException {
        JsonNode root = mapper.readTree(record.value());
        if ("OrderCreated".equals(root.path("eventType").asText())) {
            service.onOrderCreated(context(root), payload(root, InboundEvents.OrderCreated.class));
        } else {
            log.debug("Ignoring {} on {}", root.path("eventType").asText(), record.topic());
        }
    }

    @KafkaListener(topics = InboundEvents.PAYMENT_TOPIC)
    public void onPaymentEvent(ConsumerRecord<String, String> record) throws JsonProcessingException {
        JsonNode root = mapper.readTree(record.value());
        if ("PaymentFailed".equals(root.path("eventType").asText())) {
            service.onPaymentFailed(context(root), payload(root, InboundEvents.PaymentFailed.class));
        } else {
            log.debug("Ignoring {} on {}", root.path("eventType").asText(), record.topic());
        }
    }

    private static EventContext context(JsonNode root) {
        return new EventContext(
                UUID.fromString(root.path("eventId").asText()),
                UUID.fromString(root.path("orderId").asText()),
                UUID.fromString(root.path("correlationId").asText()));
    }

    private <T> T payload(JsonNode root, Class<T> type) throws JsonProcessingException {
        return mapper.treeToValue(root.path("payload"), type);
    }
}
