package dev.aadityaa.orderplatform.payment.events;

/** Our own minimal view of events produced by other services (tolerant reader: unknown fields are ignored). */
public final class InboundEvents {

    public static final String INVENTORY_TOPIC = "inventory.events";

    private InboundEvents() {
    }

    public record StockReserved(String customerId, long totalAmountMinor, String currency) {
    }
}
