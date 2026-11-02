package dev.aadityaa.orderplatform.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aadityaa.orderplatform.inventory.application.ReservationService;
import dev.aadityaa.orderplatform.inventory.events.InboundEvents;
import dev.aadityaa.orderplatform.inventory.events.InboundEvents.OrderCreated.Item;
import dev.aadityaa.orderplatform.platform.messaging.EventContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class ReservationServiceIT extends AbstractInventoryIT {

    @Autowired
    ReservationService service;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.sql("TRUNCATE outbox, processed_events, reservation_items, reservations, stock CASCADE").update();
    }

    // ---------- basics ----------

    @Test
    void reservesStockAndEmitsStockReserved() {
        seedStock("SKU-1", 10);
        EventContext ctx = newContext();

        service.onOrderCreated(ctx, order(new Item("SKU-1", 3)));

        assertThat(available("SKU-1")).isEqualTo(7);
        assertThat(reserved("SKU-1")).isEqualTo(3);
        assertThat(reservationStatus(ctx.orderId())).isEqualTo("RESERVED");
        assertThat(eventTypes(ctx.orderId())).containsExactly("StockReserved");
    }

    @Test
    void rejectsWhenStockIsInsufficientAndChangesNothing() {
        seedStock("SKU-1", 2);
        EventContext ctx = newContext();

        service.onOrderCreated(ctx, order(new Item("SKU-1", 5)));

        assertThat(available("SKU-1")).isEqualTo(2);
        assertThat(reserved("SKU-1")).isZero();
        assertThat(reservationStatus(ctx.orderId())).isNull();
        assertThat(eventTypes(ctx.orderId())).containsExactly("StockReservationFailed");
    }

    @Test
    void unknownSkuIsRejected() {
        EventContext ctx = newContext();

        service.onOrderCreated(ctx, order(new Item("NO-SUCH-SKU", 1)));

        assertThat(eventTypes(ctx.orderId())).containsExactly("StockReservationFailed");
        assertThat(failureReason(ctx.orderId())).isEqualTo("UNKNOWN_SKU");
    }

    @Test
    void multiItemOrderIsAllOrNothing() {
        seedStock("PLENTY", 100);
        seedStock("SCARCE", 1);
        EventContext ctx = newContext();

        service.onOrderCreated(ctx, order(new Item("PLENTY", 5), new Item("SCARCE", 2)));

        assertThat(available("PLENTY")).isEqualTo(100); // the available item must not be touched
        assertThat(reserved("PLENTY")).isZero();
        assertThat(eventTypes(ctx.orderId())).containsExactly("StockReservationFailed");
    }

    // ---------- idempotency ----------

    @Test
    void duplicateEventIsIgnored() {
        seedStock("SKU-1", 10);
        EventContext ctx = newContext();

        service.onOrderCreated(ctx, order(new Item("SKU-1", 3)));
        service.onOrderCreated(ctx, order(new Item("SKU-1", 3))); // redelivery of the same event

        assertThat(reserved("SKU-1")).isEqualTo(3);
        assertThat(eventTypes(ctx.orderId())).containsExactly("StockReserved");
    }

    @Test
    void sameOrderUnderADifferentEventIdDoesNotReserveTwice() {
        seedStock("SKU-1", 10);
        UUID orderId = UUID.randomUUID();

        service.onOrderCreated(new EventContext(UUID.randomUUID(), orderId, UUID.randomUUID()), order(new Item("SKU-1", 3)));
        service.onOrderCreated(new EventContext(UUID.randomUUID(), orderId, UUID.randomUUID()), order(new Item("SKU-1", 3)));

        assertThat(reserved("SKU-1")).isEqualTo(3);
        assertThat(eventTypes(orderId)).containsExactly("StockReserved");
    }

    // ---------- concurrency ----------

    @Test
    void fiftyConcurrentOrdersForTenUnitsNeverOversell() throws Exception {
        seedStock("HOT", 10);

        runConcurrently(50, i -> service.onOrderCreated(newContext(), order(new Item("HOT", 1))));

        assertThat(available("HOT")).isZero();
        assertThat(reserved("HOT")).isEqualTo(10);
        assertThat(countEvents("StockReserved")).isEqualTo(10);
        assertThat(countEvents("StockReservationFailed")).isEqualTo(40);
    }

    @Test
    void ordersLockingTheSameSkusInOppositeOrderDoNotDeadlock() throws Exception {
        seedStock("DL-A", 1000);
        seedStock("DL-B", 1000);

        // Half the orders list A then B, half list B then A. Sorted lock acquisition makes this safe;
        // with naive in-request-order locking Postgres would eventually raise a deadlock error here.
        runConcurrently(40, i -> service.onOrderCreated(newContext(),
                i % 2 == 0 ? order(new Item("DL-A", 1), new Item("DL-B", 1))
                           : order(new Item("DL-B", 1), new Item("DL-A", 1))));

        assertThat(reserved("DL-A")).isEqualTo(40);
        assertThat(reserved("DL-B")).isEqualTo(40);
        assertThat(countEvents("StockReserved")).isEqualTo(40);
    }

    // ---------- compensation ----------

    @Test
    void paymentFailedReleasesStockExactlyOnce() {
        seedStock("SKU-1", 10);
        EventContext order = newContext();
        service.onOrderCreated(order, order(new Item("SKU-1", 4)));

        service.onPaymentFailed(new EventContext(UUID.randomUUID(), order.orderId(), order.correlationId()),
                new InboundEvents.PaymentFailed("DECLINED"));
        service.onPaymentFailed(new EventContext(UUID.randomUUID(), order.orderId(), order.correlationId()),
                new InboundEvents.PaymentFailed("DECLINED")); // a second failure event for the same order

        assertThat(available("SKU-1")).isEqualTo(10);
        assertThat(reserved("SKU-1")).isZero();
        assertThat(reservationStatus(order.orderId())).isEqualTo("RELEASED");
        assertThat(eventTypes(order.orderId())).containsExactly("StockReserved", "StockReleased");
    }

    @Test
    void paymentFailedWithoutAReservationIsANoOp() {
        seedStock("SKU-1", 10);
        EventContext ctx = newContext();

        service.onPaymentFailed(ctx, new InboundEvents.PaymentFailed("DECLINED"));

        assertThat(available("SKU-1")).isEqualTo(10);
        assertThat(eventTypes(ctx.orderId())).isEmpty();
    }

    // ---------- helpers ----------

    private interface Task {
        void run(int index) throws Exception;
    }

    /** Releases all tasks at the same instant; any exception (e.g. a deadlock) fails the test. */
    private void runConcurrently(int count, Task task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                start.await();
                task.run(index);
                return null;
            }));
        }
        start.countDown();
        for (Future<Void> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
    }

    private static EventContext newContext() {
        return new EventContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    private static InboundEvents.OrderCreated order(Item... items) {
        return new InboundEvents.OrderCreated("cust-1", List.of(items), 1000, "USD");
    }

    private void seedStock(String sku, int quantity) {
        jdbc.sql("INSERT INTO stock (sku, available) VALUES (:sku, :qty)").param("sku", sku).param("qty", quantity).update();
    }

    private int available(String sku) {
        return jdbc.sql("SELECT available FROM stock WHERE sku = :sku").param("sku", sku).query(Integer.class).single();
    }

    private int reserved(String sku) {
        return jdbc.sql("SELECT reserved FROM stock WHERE sku = :sku").param("sku", sku).query(Integer.class).single();
    }

    private String reservationStatus(UUID orderId) {
        return jdbc.sql("SELECT status FROM reservations WHERE order_id = :id").param("id", orderId)
                .query(String.class).optional().orElse(null);
    }

    private List<String> eventTypes(UUID orderId) {
        return jdbc.sql("SELECT event_type FROM outbox WHERE aggregate_id = :id ORDER BY id").param("id", orderId)
                .query(String.class).list();
    }

    private String failureReason(UUID orderId) {
        return jdbc.sql("SELECT payload -> 'payload' ->> 'reason' FROM outbox WHERE aggregate_id = :id")
                .param("id", orderId).query(String.class).single();
    }

    private long countEvents(String eventType) {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE event_type = :t").param("t", eventType)
                .query(Long.class).single();
    }
}
