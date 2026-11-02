package dev.aadityaa.orderplatform.payment.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aadityaa.orderplatform.payment.application.PaymentService;
import dev.aadityaa.orderplatform.payment.events.InboundEvents;
import dev.aadityaa.orderplatform.platform.messaging.IncomingEvent;
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
        IncomingEvent event = IncomingEvent.parse(mapper, record.value());
        if ("StockReserved".equals(event.eventType())) {
            service.onStockReserved(event.context(), event.payloadAs(mapper, InboundEvents.StockReserved.class));
        } else {
            log.debug("Ignoring {} on {}", event.eventType(), record.topic());
        }
    }
}
