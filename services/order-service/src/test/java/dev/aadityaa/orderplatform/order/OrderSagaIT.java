package dev.aadityaa.orderplatform.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aadityaa.orderplatform.order.api.CreateOrderRequest;
import dev.aadityaa.orderplatform.order.application.OrderSagaService;
import dev.aadityaa.orderplatform.order.application.OrderService;
import dev.aadityaa.orderplatform.order.events.InboundEvents;
import dev.aadityaa.orderplatform.platform.messaging.EventContext;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "outbox.publisher.enabled=false"
})
class OrderSagaIT extends AbstractOrderIT {

    @Autowired OrderService orders;
    @Autowired OrderSagaService saga;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.sql("TRUNCATE idempotency_keys, order_items, orders, outbox, processed_events CASCADE").update();
    }

    @Test
    void paymentCompletedConfirmsTheOrderAndEmitsOrderConfirmed() {
        UUID id = newOrder();

        saga.onPaymentCompleted(ctx(id), new InboundEvents.PaymentCompleted("gw_1"));

        assertThat(status(id)).isEqualTo("CONFIRMED");
        assertThat(eventTypes(id)).containsExactly("OrderCreated", "OrderConfirmed");
    }

    @Test
    void stockReservationFailedRejectsTheOrderWithTheReason() {
        UUID id = newOrder();

        saga.onStockReservationFailed(ctx(id), new InboundEvents.StockReservationFailed("OUT_OF_STOCK"));

        assertThat(status(id)).isEqualTo("REJECTED");
        assertThat(orders.get(id).failureReason()).isEqualTo("OUT_OF_STOCK");
        assertThat(eventTypes(id)).containsExactly("OrderCreated", "OrderRejected");
    }

    @Test
    void paymentFailedCancelsTheOrderWithTheReason() {
        UUID id = newOrder();

        saga.onPaymentFailed(ctx(id), new InboundEvents.PaymentFailed("DECLINED"));

        assertThat(status(id)).isEqualTo("CANCELLED");
        assertThat(orders.get(id).failureReason()).isEqualTo("DECLINED");
        assertThat(eventTypes(id)).containsExactly("OrderCreated", "OrderCancelled");
    }

    @Test
    void duplicateEventIsIgnored() {
        UUID id = newOrder();
        EventContext ctx = ctx(id);

        saga.onPaymentCompleted(ctx, new InboundEvents.PaymentCompleted("gw_1"));
        saga.onPaymentCompleted(ctx, new InboundEvents.PaymentCompleted("gw_1"));

        assertThat(eventTypes(id)).containsExactly("OrderCreated", "OrderConfirmed");
    }

    @Test
    void lateEventOnATerminalOrderChangesNothingAndEmitsNothing() {
        UUID id = newOrder();
        saga.onPaymentCompleted(ctx(id), new InboundEvents.PaymentCompleted("gw_1"));

        saga.onPaymentFailed(ctx(id), new InboundEvents.PaymentFailed("DECLINED")); // arrives too late

        assertThat(status(id)).isEqualTo("CONFIRMED");
        assertThat(orders.get(id).failureReason()).isNull();
        assertThat(eventTypes(id)).containsExactly("OrderCreated", "OrderConfirmed");
        // the late event is still marked processed, so it will not be examined again
        assertThat(count("processed_events")).isEqualTo(2);
    }

    @Test
    void eventForAnUnknownOrderFailsAndLeavesNoProcessedMarker() {
        assertThatThrownBy(() -> saga.onPaymentCompleted(ctx(UUID.randomUUID()), new InboundEvents.PaymentCompleted("gw")))
                .isInstanceOf(IllegalStateException.class);

        // the marker rolled back with the failed transaction, so a retry or the dead-letter path sees a clean slate
        assertThat(count("processed_events")).isZero();
    }

    @Test
    void conflictingOutcomesArrivingTogetherProduceExactlyOneTerminalState() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 25; round++) {
                UUID id = newOrder();
                CountDownLatch start = new CountDownLatch(1);
                Future<Void> confirm = pool.submit(() -> {
                    start.await();
                    saga.onPaymentCompleted(ctx(id), new InboundEvents.PaymentCompleted("gw"));
                    return null;
                });
                Future<Void> cancel = pool.submit(() -> {
                    start.await();
                    saga.onPaymentFailed(ctx(id), new InboundEvents.PaymentFailed("DECLINED"));
                    return null;
                });
                start.countDown();
                tolerateOptimisticLockFailure(confirm);
                tolerateOptimisticLockFailure(cancel);

                // the consumer redelivers whichever handler failed: the outcome must not change
                saga.onPaymentCompleted(ctx(id), new InboundEvents.PaymentCompleted("gw"));
                saga.onPaymentFailed(ctx(id), new InboundEvents.PaymentFailed("DECLINED"));

                List<String> terminal = eventTypes(id).stream().filter(t -> !t.equals("OrderCreated")).toList();
                assertThat(terminal).hasSize(1);
                assertThat(terminal.get(0))
                        .isEqualTo(status(id).equals("CONFIRMED") ? "OrderConfirmed" : "OrderCancelled");
            }
        } finally {
            pool.shutdown();
        }
    }

    /** The loser of a race may fail with an optimistic-lock error (its whole transaction rolls back); nothing else is acceptable. */
    private static void tolerateOptimisticLockFailure(Future<Void> future) throws Exception {
        try {
            future.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            assertThat(e.getCause()).isInstanceOf(ObjectOptimisticLockingFailureException.class);
        }
    }

    private UUID newOrder() {
        return orders.create("saga-" + UUID.randomUUID(), new CreateOrderRequest("cust-1", "jane@example.com", "USD",
                List.of(new CreateOrderRequest.Item("SKU-RED-MUG", 2, 1250)))).id();
    }

    private static EventContext ctx(UUID orderId) {
        return new EventContext(UUID.randomUUID(), orderId, UUID.randomUUID());
    }

    private String status(UUID id) {
        return orders.get(id).status();
    }

    private List<String> eventTypes(UUID id) {
        return jdbc.sql("SELECT event_type FROM outbox WHERE aggregate_id = :id ORDER BY id").param("id", id)
                .query(String.class).list();
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }
}
