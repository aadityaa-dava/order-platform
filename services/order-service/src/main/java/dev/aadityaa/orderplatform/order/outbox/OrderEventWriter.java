package dev.aadityaa.orderplatform.order.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aadityaa.orderplatform.order.domain.Order;
import dev.aadityaa.orderplatform.order.events.EventEnvelope;
import dev.aadityaa.orderplatform.order.events.OrderEvents;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The only place that builds order events and writes them to the outbox.
 * Every method must be called inside the transaction that changed the order.
 */
@Component
public class OrderEventWriter {

    private static final String PRODUCER = "order-service";
    private static final int VERSION = 1;

    private final OutboxRepository outbox;
    private final ObjectMapper mapper;

    public OrderEventWriter(OutboxRepository outbox, ObjectMapper mapper) {
        this.outbox = outbox;
        this.mapper = mapper;
    }

    public void orderCreated(Order order) {
        var items = order.getItems().stream()
                .map(i -> new OrderEvents.OrderCreated.Item(i.getSku(), i.getQuantity(), i.getUnitPriceMinor()))
                .toList();
        write(order, "OrderCreated", new OrderEvents.OrderCreated(
                order.getCustomerId(), order.getCustomerEmail(), items,
                order.getTotalAmountMinor(), order.getCurrency()));
    }

    public void orderConfirmed(Order order) {
        write(order, "OrderConfirmed", new OrderEvents.OrderConfirmed(
                order.getCustomerId(), order.getCustomerEmail(),
                order.getTotalAmountMinor(), order.getCurrency()));
    }

    public void orderRejected(Order order) {
        write(order, "OrderRejected", new OrderEvents.OrderRejected(
                order.getCustomerId(), order.getCustomerEmail(), order.getFailureReason()));
    }

    public void orderCancelled(Order order) {
        write(order, "OrderCancelled", new OrderEvents.OrderCancelled(
                order.getCustomerId(), order.getCustomerEmail(), order.getFailureReason()));
    }

    private <T> void write(Order order, String eventType, T payload) {
        var envelope = new EventEnvelope<>(
                UUID.randomUUID(), eventType, VERSION, Instant.now(),
                order.getId(), order.getCorrelationId(), PRODUCER, payload);
        try {
            outbox.insert(envelope.eventId(), order.getId(), eventType,
                    OrderEvents.TOPIC, mapper.writeValueAsString(envelope));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize " + eventType, e);
        }
    }
}
