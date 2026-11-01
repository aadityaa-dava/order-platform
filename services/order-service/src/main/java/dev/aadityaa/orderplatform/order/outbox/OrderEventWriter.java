package dev.aadityaa.orderplatform.order.outbox;

import dev.aadityaa.orderplatform.order.domain.Order;
import dev.aadityaa.orderplatform.order.events.OrderEvents;
import dev.aadityaa.orderplatform.platform.messaging.OutboxWriter;
import org.springframework.stereotype.Component;

/**
 * Maps order state changes to order events. Every method must run inside the
 * transaction that changed the order (the shared OutboxWriter enforces this).
 */
@Component
public class OrderEventWriter {

    private static final int VERSION = 1;

    private final OutboxWriter outbox;

    public OrderEventWriter(OutboxWriter outbox) {
        this.outbox = outbox;
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

    private void write(Order order, String eventType, Object payload) {
        outbox.write(OrderEvents.TOPIC, order.getId(), order.getCorrelationId(), eventType, VERSION, payload);
    }
}
