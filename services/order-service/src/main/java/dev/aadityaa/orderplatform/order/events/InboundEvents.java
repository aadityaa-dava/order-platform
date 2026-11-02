package dev.aadityaa.orderplatform.order.events;

/** Our own minimal view of events produced by other services (tolerant reader: unknown fields are ignored). */
public final class InboundEvents {

    public static final String INVENTORY_TOPIC = "inventory.events";
    public static final String PAYMENT_TOPIC = "payment.events";

    private InboundEvents() {
    }

    public record StockReservationFailed(String reason) {
    }

    public record PaymentCompleted(String gatewayReference) {
    }

    public record PaymentFailed(String reason) {
    }
}
