package dev.aadityaa.orderplatform.inventory.events;

import java.util.List;

/** Our own minimal view of events produced by other services (tolerant reader: unknown fields are ignored). */
public final class InboundEvents {

    public static final String ORDER_TOPIC = "order.events";
    public static final String PAYMENT_TOPIC = "payment.events";

    private InboundEvents() {
    }

    public record OrderCreated(String customerId, List<Item> items, long totalAmountMinor, String currency) {
        public record Item(String sku, int quantity) {
        }
    }

    public record PaymentFailed(String reason) {
    }
}
