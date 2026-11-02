package dev.aadityaa.orderplatform.inventory.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aadityaa.orderplatform.inventory.application.ReservationService;
import dev.aadityaa.orderplatform.inventory.events.InboundEvents;
import dev.aadityaa.orderplatform.platform.messaging.IncomingEvent;
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
        IncomingEvent event = IncomingEvent.parse(mapper, record.value());
        if ("OrderCreated".equals(event.eventType())) {
            service.onOrderCreated(event.context(), event.payloadAs(mapper, InboundEvents.OrderCreated.class));
        } else {
            log.debug("Ignoring {} on {}", event.eventType(), record.topic());
        }
    }

    @KafkaListener(topics = InboundEvents.PAYMENT_TOPIC)
    public void onPaymentEvent(ConsumerRecord<String, String> record) throws JsonProcessingException {
        IncomingEvent event = IncomingEvent.parse(mapper, record.value());
        if ("PaymentFailed".equals(event.eventType())) {
            service.onPaymentFailed(event.context(), event.payloadAs(mapper, InboundEvents.PaymentFailed.class));
        } else {
            log.debug("Ignoring {} on {}", event.eventType(), record.topic());
        }
    }
}
