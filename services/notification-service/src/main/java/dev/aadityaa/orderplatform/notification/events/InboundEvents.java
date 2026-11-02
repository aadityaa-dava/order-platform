package dev.aadityaa.orderplatform.notification.events;

/** Our own minimal view of the order events we care about (tolerant reader: unknown fields are ignored). */
public final class InboundEvents {

    public static final String ORDER_TOPIC = "order.events";

    private InboundEvents() {
    }

    public record OrderConfirmed(String customerEmail, long totalAmountMinor, String currency) {
    }

    public record OrderRejected(String customerEmail, String reason) {
    }

    public record OrderCancelled(String customerEmail, String reason) {
    }
}
