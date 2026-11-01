package dev.aadityaa.orderplatform.inventory.events;

import java.util.List;
import java.util.UUID;

/** Payloads of the events this service publishes on inventory.events. */
public final class InventoryEvents {

    public static final String TOPIC = "inventory.events";

    private InventoryEvents() {
    }

    public record StockReserved(UUID reservationId, List<Item> items, String customerId,
                                long totalAmountMinor, String currency) {
        public record Item(String sku, int quantity) {
        }
    }

    public record StockReservationFailed(String reason, List<Unavailable> unavailable) {
        public record Unavailable(String sku, int requested, int available) {
        }
    }

    public record StockReleased(UUID reservationId, String reason) {
    }
}
