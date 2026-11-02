package dev.aadityaa.orderplatform.order.application;

import dev.aadityaa.orderplatform.order.domain.Order;
import dev.aadityaa.orderplatform.order.domain.OrderRepository;
import dev.aadityaa.orderplatform.order.events.InboundEvents;
import dev.aadityaa.orderplatform.order.outbox.OrderEventWriter;
import dev.aadityaa.orderplatform.platform.messaging.EventContext;
import dev.aadityaa.orderplatform.platform.messaging.ProcessedEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reacts to the outcomes of the other services and drives the order state machine. */
@Service
public class OrderSagaService {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaService.class);

    private final OrderRepository orders;
    private final ProcessedEventRepository processed;
    private final OrderEventWriter events;
    private final MeterRegistry registry;

    public OrderSagaService(OrderRepository orders, ProcessedEventRepository processed, OrderEventWriter events,
                            MeterRegistry registry) {
        this.orders = orders;
        this.processed = processed;
        this.events = events;
        this.registry = registry;
    }

    @Transactional
    public void onStockReservationFailed(EventContext ctx, InboundEvents.StockReservationFailed failed) {
        apply(ctx, order -> order.reject(failed.reason()), events::orderRejected);
    }

    @Transactional
    public void onPaymentCompleted(EventContext ctx, InboundEvents.PaymentCompleted completed) {
        apply(ctx, Order::confirm, events::orderConfirmed);
    }

    @Transactional
    public void onPaymentFailed(EventContext ctx, InboundEvents.PaymentFailed failed) {
        apply(ctx, order -> order.cancel(failed.reason()), events::orderCancelled);
    }

    /** One transaction: dedupe marker + state change + outbox event. */
    private void apply(EventContext ctx, Predicate<Order> transition, Consumer<Order> emit) {
        if (!processed.markProcessed(ctx.eventId())) {
            log.info("Duplicate event {} ignored", ctx.eventId());
            return;
        }
        Order order = orders.findById(ctx.orderId())
                .orElseThrow(() -> new IllegalStateException("Event " + ctx.eventId() + " refers to unknown order " + ctx.orderId()));

        if (transition.test(order)) {
            emit.accept(order);
            recordOutcome(order);
            log.info("Order {} is now {}", order.getId(), order.getStatus());
        } else {
            log.info("Order {} is already {}; event {} ignored", order.getId(), order.getStatus(), ctx.eventId());
        }
    }

    /** The end-to-end number the platform is about: how long from order accepted to a final outcome. */
    private void recordOutcome(Order order) {
        String status = order.getStatus().name();
        Counter.builder("orders.completed").description("Orders that reached a final state")
                .tag("status", status).register(registry).increment();
        Timer.builder("orders.saga.duration").description("Time from order accepted to final outcome")
                .tag("status", status).publishPercentileHistogram().register(registry)
                .record(Duration.between(order.getCreatedAt(), Instant.now()));
    }
}
