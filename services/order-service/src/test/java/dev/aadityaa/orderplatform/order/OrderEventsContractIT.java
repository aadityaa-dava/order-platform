package dev.aadityaa.orderplatform.order;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aadityaa.orderplatform.contracts.EventContracts;
import dev.aadityaa.orderplatform.order.api.CreateOrderRequest;
import dev.aadityaa.orderplatform.order.application.OrderSagaService;
import dev.aadityaa.orderplatform.order.application.OrderService;
import dev.aadityaa.orderplatform.order.events.InboundEvents;
import dev.aadityaa.orderplatform.platform.messaging.EventContext;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Producer contract: every event order-service writes to its outbox must satisfy the shared schema. */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "outbox.publisher.enabled=false"
})
class OrderEventsContractIT extends AbstractOrderIT {

    @Autowired OrderService orders;
    @Autowired OrderSagaService saga;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.sql("TRUNCATE idempotency_keys, order_items, orders, outbox, processed_events CASCADE").update();
    }

    @Test
    void everyEventTheOrderServiceWritesHonoursTheContract() {
        UUID confirmed = newOrder();
        UUID rejected = newOrder();
        UUID cancelled = newOrder();
        saga.onPaymentCompleted(ctx(confirmed), new InboundEvents.PaymentCompleted("gw_1"));
        saga.onStockReservationFailed(ctx(rejected), new InboundEvents.StockReservationFailed("OUT_OF_STOCK"));
        saga.onPaymentFailed(ctx(cancelled), new InboundEvents.PaymentFailed("DECLINED"));

        assertOutboxHonoursContract(Set.of("OrderCreated", "OrderConfirmed", "OrderRejected", "OrderCancelled"));
    }

    private void assertOutboxHonoursContract(Set<String> expectedTypes) {
        List<Map.Entry<String, String>> rows = jdbc.sql("SELECT event_type, payload::text AS payload FROM outbox")
                .query((rs, n) -> Map.entry(rs.getString("event_type"), rs.getString("payload"))).list();
        Set<String> seen = new HashSet<>();
        for (var row : rows) {
            assertThat(EventContracts.validate(row.getKey(), row.getValue()))
                    .as("%s violates its contract: %s", row.getKey(), row.getValue()).isEmpty();
            seen.add(row.getKey());
        }
        assertThat(seen).isEqualTo(expectedTypes);
    }

    private UUID newOrder() {
        return orders.create("contract-" + UUID.randomUUID(), new CreateOrderRequest("cust-1", "jane@example.com", "USD",
                List.of(new CreateOrderRequest.Item("SKU-RED-MUG", 2, 1250)))).id();
    }

    private static EventContext ctx(UUID orderId) {
        return new EventContext(UUID.randomUUID(), orderId, UUID.randomUUID());
    }
}
