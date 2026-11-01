package dev.aadityaa.orderplatform.order.api;

import dev.aadityaa.orderplatform.order.domain.Order;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        String status,
        String customerId,
        long totalAmountMinor,
        String currency,
        List<Item> items,
        String failureReason,
        Instant createdAt,
        Instant updatedAt) {

    public record Item(String sku, int quantity, long unitPriceMinor) {
    }

    public static OrderResponse from(Order o) {
        return new OrderResponse(
                o.getId(), o.getStatus().name(), o.getCustomerId(), o.getTotalAmountMinor(), o.getCurrency(),
                o.getItems().stream().map(i -> new Item(i.getSku(), i.getQuantity(), i.getUnitPriceMinor())).toList(),
                o.getFailureReason(), o.getCreatedAt(), o.getUpdatedAt());
    }
}
