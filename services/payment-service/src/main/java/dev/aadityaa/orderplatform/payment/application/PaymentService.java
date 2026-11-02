package dev.aadityaa.orderplatform.payment.application;

import dev.aadityaa.orderplatform.payment.events.InboundEvents;
import dev.aadityaa.orderplatform.payment.events.PaymentEvents;
import dev.aadityaa.orderplatform.payment.gateway.GatewayOutcome;
import dev.aadityaa.orderplatform.payment.gateway.PaymentGatewayClient;
import dev.aadityaa.orderplatform.payment.persistence.PaymentRepository;
import dev.aadityaa.orderplatform.platform.messaging.OutboxWriter;
import dev.aadityaa.orderplatform.platform.messaging.ProcessedEventRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final int EVENT_VERSION = 1;

    private final PaymentRepository payments;
    private final PaymentGatewayClient gateway;
    private final ProcessedEventRepository processed;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;

    public PaymentService(PaymentRepository payments, PaymentGatewayClient gateway,
                          ProcessedEventRepository processed, OutboxWriter outbox, TransactionTemplate tx) {
        this.payments = payments;
        this.gateway = gateway;
        this.processed = processed;
        this.outbox = outbox;
        this.tx = tx;
    }

    /**
     * Deliberately NOT @Transactional: the slow external call must not hold a database connection.
     * Order: (1) already paid? (2) call gateway with a stable idempotency key (3) short transaction to record.
     */
    public void onStockReserved(EventContext ctx, InboundEvents.StockReserved reserved) {
        if (payments.existsByOrderId(ctx.orderId())) {
            log.info("Payment for order {} already recorded; ignoring event {}", ctx.orderId(), ctx.eventId());
            return;
        }

        GatewayOutcome outcome = gateway.charge(
                "pay-" + ctx.orderId(), reserved.totalAmountMinor(), reserved.currency(), reserved.customerId());

        try {
            tx.executeWithoutResult(status -> record(ctx, reserved, outcome));
        } catch (DataIntegrityViolationException e) {
            if (payments.existsByOrderId(ctx.orderId())) {
                log.info("Concurrent duplicate for order {}; payment already recorded", ctx.orderId());
                return;
            }
            throw e; // a constraint failed for some other reason: that is a bug, do not hide it
        }
    }

    private void record(EventContext ctx, InboundEvents.StockReserved reserved, GatewayOutcome outcome) {
        if (!processed.markProcessed(ctx.eventId())) {
            log.info("Duplicate event {} ignored", ctx.eventId());
            return;
        }
        UUID paymentId = UUID.randomUUID();
        if (outcome.approved()) {
            payments.insert(paymentId, ctx.orderId(), reserved.customerId(), reserved.totalAmountMinor(),
                    reserved.currency(), "COMPLETED", null, outcome.reference(), outcome.attempts());
            write(ctx, "PaymentCompleted", new PaymentEvents.PaymentCompleted(
                    paymentId, reserved.totalAmountMinor(), reserved.currency(), outcome.reference()));
            log.info("Order {} paid (payment {}, {} attempt(s))", ctx.orderId(), paymentId, outcome.attempts());
        } else {
            payments.insert(paymentId, ctx.orderId(), reserved.customerId(), reserved.totalAmountMinor(),
                    reserved.currency(), "FAILED", outcome.failureReason(), null, outcome.attempts());
            write(ctx, "PaymentFailed", new PaymentEvents.PaymentFailed(
                    outcome.failureReason(), "Gateway outcome after " + outcome.attempts() + " attempt(s)"));
            log.info("Order {} payment failed: {}", ctx.orderId(), outcome.failureReason());
        }
    }

    private void write(EventContext ctx, String eventType, Object payload) {
        outbox.write(PaymentEvents.TOPIC, ctx.orderId(), ctx.correlationId(), eventType, EVENT_VERSION, payload);
    }
}
