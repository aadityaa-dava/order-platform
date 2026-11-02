package dev.aadityaa.orderplatform.notification.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aadityaa.orderplatform.notification.application.NotificationService;
import dev.aadityaa.orderplatform.notification.application.NotificationTemplates;
import dev.aadityaa.orderplatform.notification.events.InboundEvents;
import dev.aadityaa.orderplatform.platform.messaging.IncomingEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class NotificationEventListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventListener.class);

    private final NotificationService service;
    private final ObjectMapper mapper;

    public NotificationEventListener(NotificationService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @KafkaListener(topics = InboundEvents.ORDER_TOPIC)
    public void onOrderEvent(ConsumerRecord<String, String> record) throws JsonProcessingException {
        IncomingEvent event = IncomingEvent.parse(mapper, record.value());
        var ctx = event.context();
        switch (event.eventType()) {
            case "OrderConfirmed" -> {
                var p = event.payloadAs(mapper, InboundEvents.OrderConfirmed.class);
                service.notifyCustomer(ctx, "ORDER_CONFIRMED", p.customerEmail(),
                        NotificationTemplates.confirmed(ctx.orderId(), p.totalAmountMinor(), p.currency()));
            }
            case "OrderRejected" -> {
                var p = event.payloadAs(mapper, InboundEvents.OrderRejected.class);
                service.notifyCustomer(ctx, "ORDER_REJECTED", p.customerEmail(),
                        NotificationTemplates.rejected(ctx.orderId(), p.reason()));
            }
            case "OrderCancelled" -> {
                var p = event.payloadAs(mapper, InboundEvents.OrderCancelled.class);
                service.notifyCustomer(ctx, "ORDER_CANCELLED", p.customerEmail(),
                        NotificationTemplates.cancelled(ctx.orderId(), p.reason()));
            }
            default -> log.debug("Ignoring {} on {}", event.eventType(), record.topic());
        }
    }
}
