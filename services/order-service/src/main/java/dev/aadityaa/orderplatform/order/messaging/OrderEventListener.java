package dev.aadityaa.orderplatform.order.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aadityaa.orderplatform.order.application.OrderSagaService;
import dev.aadityaa.orderplatform.order.events.InboundEvents;
import dev.aadityaa.orderplatform.platform.messaging.IncomingEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OrderEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);

    private final OrderSagaService saga;
    private final ObjectMapper mapper;

    public OrderEventListener(OrderSagaService saga, ObjectMapper mapper) {
        this.saga = saga;
        this.mapper = mapper;
    }

    @KafkaListener(topics = InboundEvents.INVENTORY_TOPIC)
    public void onInventoryEvent(ConsumerRecord<String, String> record) throws JsonProcessingException {
        IncomingEvent event = IncomingEvent.parse(mapper, record.value());
        if ("StockReservationFailed".equals(event.eventType())) {
            saga.onStockReservationFailed(event.context(), event.payloadAs(mapper, InboundEvents.StockReservationFailed.class));
        } else {
            log.debug("Ignoring {} on {}", event.eventType(), record.topic());
        }
    }

    @KafkaListener(topics = InboundEvents.PAYMENT_TOPIC)
    public void onPaymentEvent(ConsumerRecord<String, String> record) throws JsonProcessingException {
        IncomingEvent event = IncomingEvent.parse(mapper, record.value());
        switch (event.eventType()) {
            case "PaymentCompleted" ->
                    saga.onPaymentCompleted(event.context(), event.payloadAs(mapper, InboundEvents.PaymentCompleted.class));
            case "PaymentFailed" ->
                    saga.onPaymentFailed(event.context(), event.payloadAs(mapper, InboundEvents.PaymentFailed.class));
            default -> log.debug("Ignoring {} on {}", event.eventType(), record.topic());
        }
    }
}
