package dev.aadityaa.orderplatform.order.events;

import java.util.List;

/** Payloads of the events published on order.events. */
public final class OrderEvents {

    public static final String TOPIC = "order.events";

    private OrderEvents() {
    }

    public record OrderCreated(String customerId, String customerEmail, List<Item> items,
                               long totalAmountMinor, String currency) {
        public record Item(String sku, int quantity, long unitPriceMinor) {
        }
    }

    public record OrderConfirmed(String customerId, String customerEmail, long totalAmountMinor, String currency) {
    }

    public record OrderRejected(String customerId, String customerEmail, String reason) {
    }

    public record OrderCancelled(String customerId, String customerEmail, String reason) {
    }
}
