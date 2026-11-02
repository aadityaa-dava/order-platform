package dev.aadityaa.orderplatform.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aadityaa.orderplatform.contracts.EventContracts;
import dev.aadityaa.orderplatform.inventory.application.ReservationService;
import dev.aadityaa.orderplatform.inventory.events.InboundEvents;
import dev.aadityaa.orderplatform.platform.messaging.EventContext;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Producer contract: every event inventory-service writes to its outbox must satisfy the shared schema. */
class InventoryEventsContractIT extends AbstractInventoryIT {

    @Autowired ReservationService service;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.sql("TRUNCATE outbox, processed_events, reservation_items, reservations, stock CASCADE").update();
        jdbc.sql("INSERT INTO stock (sku, available) VALUES ('C-1', 10)").update();
    }

    @Test
    void everyEventTheInventoryServiceWritesHonoursTheContract() {
        EventContext reserved = newContext();
        service.onOrderCreated(reserved, order("C-1", 2));                       // StockReserved
        service.onOrderCreated(newContext(), order("C-1", 99));                  // StockReservationFailed: OUT_OF_STOCK
        service.onOrderCreated(newContext(), order("NO-SUCH-SKU", 1));           // StockReservationFailed: UNKNOWN_SKU
        service.onPaymentFailed(new EventContext(UUID.randomUUID(), reserved.orderId(), reserved.correlationId()),
                new InboundEvents.PaymentFailed("DECLINED"));                    // StockReleased

        List<Map.Entry<String, String>> rows = jdbc.sql("SELECT event_type, payload::text AS payload FROM outbox")
                .query((rs, n) -> Map.entry(rs.getString("event_type"), rs.getString("payload"))).list();
        Set<String> seen = new HashSet<>();
        for (var row : rows) {
            assertThat(EventContracts.validate(row.getKey(), row.getValue()))
                    .as("%s violates its contract: %s", row.getKey(), row.getValue()).isEmpty();
            seen.add(row.getKey());
        }
        assertThat(seen).isEqualTo(Set.of("StockReserved", "StockReservationFailed", "StockReleased"));
    }

    private static EventContext newContext() {
        return new EventContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    private static InboundEvents.OrderCreated order(String sku, int quantity) {
        return new InboundEvents.OrderCreated("cust-1", List.of(new InboundEvents.OrderCreated.Item(sku, quantity)), 2500, "USD");
    }
}
