package dev.aadityaa.orderplatform.payment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aadityaa.orderplatform.payment.application.PaymentService;
import dev.aadityaa.orderplatform.payment.events.InboundEvents;
import dev.aadityaa.orderplatform.payment.gateway.GatewaySettings;
import dev.aadityaa.orderplatform.payment.gateway.SimulatedPaymentGateway;
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

class PaymentServiceIT extends AbstractPaymentIT {

    @Autowired PaymentService service;
    @Autowired SimulatedPaymentGateway gateway;
    @Autowired GatewaySettings settings;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE payments, outbox, processed_events").update();
        settings.setMinLatencyMs(0);
        settings.setMaxLatencyMs(0);
        settings.setDeclineRate(0);
        settings.setErrorRate(0);
        settings.setTimeoutRate(0);
        settings.setClientTimeoutMs(500);
        settings.setMaxAttempts(3);
        settings.setBackoffMs(1);
    }

    @Test
    void approvedPaymentIsRecordedAndEmitsPaymentCompleted() {
        EventContext ctx = newContext();

        service.onStockReserved(ctx, stockReserved());

        assertThat(paymentStatus(ctx.orderId())).isEqualTo("COMPLETED");
        assertThat(attempts(ctx.orderId())).isEqualTo(1);
        assertThat(eventTypes(ctx.orderId())).containsExactly("PaymentCompleted");
    }

    @Test
    void declinedPaymentIsRecordedAndEmitsPaymentFailed() {
        settings.setDeclineRate(1.0);
        EventContext ctx = newContext();

        service.onStockReserved(ctx, stockReserved());

        assertThat(paymentStatus(ctx.orderId())).isEqualTo("FAILED");
        assertThat(failureReason(ctx.orderId())).isEqualTo("DECLINED");
        assertThat(attempts(ctx.orderId())).isEqualTo(1); // a decline is final, not retried
        assertThat(eventTypes(ctx.orderId())).containsExactly("PaymentFailed");
    }

    @Test
    void redeliveredEventDoesNotChargeOrRecordTwice() {
        EventContext ctx = newContext();
        long chargesBefore = gateway.approvedChargeCount();

        service.onStockReserved(ctx, stockReserved());
        service.onStockReserved(ctx, stockReserved());

        assertThat(paymentCount(ctx.orderId())).isEqualTo(1);
        assertThat(eventTypes(ctx.orderId())).containsExactly("PaymentCompleted");
        assertThat(gateway.approvedChargeCount() - chargesBefore).isEqualTo(1);
    }

    @Test
    void sameOrderUnderADifferentEventIdIsIgnored() {
        UUID orderId = UUID.randomUUID();

        service.onStockReserved(new EventContext(UUID.randomUUID(), orderId, UUID.randomUUID()), stockReserved());
        service.onStockReserved(new EventContext(UUID.randomUUID(), orderId, UUID.randomUUID()), stockReserved());

        assertThat(paymentCount(orderId)).isEqualTo(1);
        assertThat(eventTypes(orderId)).containsExactly("PaymentCompleted");
    }

    @Test
    void persistentGatewayErrorsAreRetriedThenReportedAsFailure() {
        settings.setErrorRate(1.0);
        EventContext ctx = newContext();

        service.onStockReserved(ctx, stockReserved());

        assertThat(paymentStatus(ctx.orderId())).isEqualTo("FAILED");
        assertThat(failureReason(ctx.orderId())).isEqualTo("GATEWAY_ERROR");
        assertThat(attempts(ctx.orderId())).isEqualTo(3);
        assertThat(eventTypes(ctx.orderId())).containsExactly("PaymentFailed");
    }

    @Test
    void timeoutAfterTheChargeStillEndsAsExactlyOneSuccessfulPayment() {
        settings.setTimeoutRate(1.0);   // gateway charges, then answers too late
        settings.setClientTimeoutMs(100);
        EventContext ctx = newContext();
        long chargesBefore = gateway.approvedChargeCount();

        service.onStockReserved(ctx, stockReserved());

        assertThat(paymentStatus(ctx.orderId())).isEqualTo("COMPLETED");
        assertThat(attempts(ctx.orderId())).isEqualTo(2); // attempt 1 timed out, attempt 2 replayed the stored result
        assertThat(gateway.approvedChargeCount() - chargesBefore).isEqualTo(1);
    }

    @Test
    void concurrentCopiesOfTheSameEventProduceOnePayment() throws Exception {
        EventContext ctx = newContext();
        long chargesBefore = gateway.approvedChargeCount();

        runConcurrently(8, i -> service.onStockReserved(ctx, stockReserved()));

        assertThat(paymentCount(ctx.orderId())).isEqualTo(1);
        assertThat(eventTypes(ctx.orderId())).containsExactly("PaymentCompleted");
        assertThat(gateway.approvedChargeCount() - chargesBefore).isEqualTo(1);
    }

    @Test
    void concurrentEventsWithDifferentIdsForTheSameOrderProduceOnePaymentAndRollBackTheLosers() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();

        // distinct event ids pass the dedupe check, so the UNIQUE(order_id) violation path is exercised
        runConcurrently(8, i -> service.onStockReserved(
                new EventContext(UUID.randomUUID(), orderId, correlationId), stockReserved()));

        assertThat(paymentCount(orderId)).isEqualTo(1);
        assertThat(eventTypes(orderId)).containsExactly("PaymentCompleted");
        // losing transactions rolled back completely, including their processed-event markers
        assertThat(jdbc.sql("SELECT count(*) FROM processed_events").query(Long.class).single()).isEqualTo(1);
    }

    // ---------- helpers ----------

    private interface Task {
        void run(int index) throws Exception;
    }

    private void runConcurrently(int count, Task task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
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

    private static InboundEvents.StockReserved stockReserved() {
        return new InboundEvents.StockReserved("cust-1", 2500, "USD");
    }

    private long paymentCount(UUID orderId) {
        return jdbc.sql("SELECT count(*) FROM payments WHERE order_id = :id").param("id", orderId).query(Long.class).single();
    }

    private String paymentStatus(UUID orderId) {
        return jdbc.sql("SELECT status FROM payments WHERE order_id = :id").param("id", orderId).query(String.class).single();
    }

    private String failureReason(UUID orderId) {
        return jdbc.sql("SELECT failure_reason FROM payments WHERE order_id = :id").param("id", orderId).query(String.class).single();
    }

    private int attempts(UUID orderId) {
        return jdbc.sql("SELECT attempts FROM payments WHERE order_id = :id").param("id", orderId).query(Integer.class).single();
    }

    private List<String> eventTypes(UUID orderId) {
        return jdbc.sql("SELECT event_type FROM outbox WHERE aggregate_id = :id ORDER BY id").param("id", orderId)
                .query(String.class).list();
    }
}
