package dev.aadityaa.orderplatform.payment.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aadityaa.orderplatform.payment.application.EventContext;
import dev.aadityaa.orderplatform.payment.application.PaymentService;
import dev.aadityaa.orderplatform.payment.events.InboundEvents;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);

    private final PaymentService service;
    private final ObjectMapper mapper;

    public PaymentEventListener(PaymentService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @KafkaListener(topics = InboundEvents.INVENTORY_TOPIC)
    public void onInventoryEvent(ConsumerRecord<String, String> record) throws JsonProcessingException {
        JsonNode root = mapper.readTree(record.value());
        if ("StockReserved".equals(root.path("eventType").asText())) {
            EventContext ctx = new EventContext(
                    UUID.fromString(root.path("eventId").asText()),
                    UUID.fromString(root.path("orderId").asText()),
                    UUID.fromString(root.path("correlationId").asText()));
            service.onStockReserved(ctx, mapper.treeToValue(root.path("payload"), InboundEvents.StockReserved.class));
        } else {
            log.debug("Ignoring {} on {}", root.path("eventType").asText(), record.topic());
        }
    }
}
