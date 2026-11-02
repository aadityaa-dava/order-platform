package dev.aadityaa.orderplatform.inventory.application;

import dev.aadityaa.orderplatform.platform.messaging.EventContext;

import dev.aadityaa.orderplatform.inventory.events.InboundEvents;
import dev.aadityaa.orderplatform.inventory.events.InventoryEvents;
import dev.aadityaa.orderplatform.inventory.reservation.ReservationRepository;
import dev.aadityaa.orderplatform.inventory.stock.StockRepository;
import dev.aadityaa.orderplatform.platform.messaging.OutboxWriter;
import dev.aadityaa.orderplatform.platform.messaging.ProcessedEventRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);
    private static final int EVENT_VERSION = 1;

    private final StockRepository stock;
    private final ReservationRepository reservations;
    private final ProcessedEventRepository processed;
    private final OutboxWriter outbox;

    public ReservationService(StockRepository stock, ReservationRepository reservations,
                              ProcessedEventRepository processed, OutboxWriter outbox) {
        this.stock = stock;
        this.reservations = reservations;
        this.processed = processed;
        this.outbox = outbox;
    }

    /** One transaction: dedupe marker + stock change + reservation + outbox event, all or nothing. */
    @Transactional
    public void onOrderCreated(EventContext ctx, InboundEvents.OrderCreated order) {
        if (!processed.markProcessed(ctx.eventId())) {
            log.info("Duplicate event {} ignored", ctx.eventId());
            return;
        }
        if (reservations.existsByOrderId(ctx.orderId())) {
            log.warn("Order {} already has a reservation; ignoring event {}", ctx.orderId(), ctx.eventId());
            return;
        }

        Map<String, Integer> requested = new TreeMap<>(); // sorted => consistent lock order
        for (var item : order.items()) {
            requested.merge(item.sku(), item.quantity(), Integer::sum);
        }
        Map<String, Integer> available = stock.lockAndReadAvailable(requested.keySet());

        List<InventoryEvents.StockReservationFailed.Unavailable> unavailable = new ArrayList<>();
        boolean unknownSku = false;
        for (var entry : requested.entrySet()) {
            Integer have = available.get(entry.getKey());
            if (have == null) {
                unknownSku = true;
                unavailable.add(new InventoryEvents.StockReservationFailed.Unavailable(entry.getKey(), entry.getValue(), 0));
            } else if (have < entry.getValue()) {
                unavailable.add(new InventoryEvents.StockReservationFailed.Unavailable(entry.getKey(), entry.getValue(), have));
            }
        }

        if (!unavailable.isEmpty()) {
            String reason = unknownSku ? "UNKNOWN_SKU" : "OUT_OF_STOCK";
            write(ctx, "StockReservationFailed", new InventoryEvents.StockReservationFailed(reason, unavailable));
            log.info("Order {} rejected: {}", ctx.orderId(), reason);
            return;
        }

        UUID reservationId = UUID.randomUUID();
        reservations.insert(reservationId, ctx.orderId(), requested);
        requested.forEach(stock::reserve);

        var items = requested.entrySet().stream()
                .map(e -> new InventoryEvents.StockReserved.Item(e.getKey(), e.getValue()))
                .toList();
        write(ctx, "StockReserved", new InventoryEvents.StockReserved(
                reservationId, items, order.customerId(), order.totalAmountMinor(), order.currency()));
        log.info("Order {} reserved as {}", ctx.orderId(), reservationId);
    }

    /** Compensating action. Idempotent: no reservation, or already released, means nothing to do. */
    @Transactional
    public void onPaymentFailed(EventContext ctx, InboundEvents.PaymentFailed failed) {
        if (!processed.markProcessed(ctx.eventId())) {
            log.info("Duplicate event {} ignored", ctx.eventId());
            return;
        }
        var reservation = reservations.findByOrderIdForUpdate(ctx.orderId());
        if (reservation.isEmpty() || !"RESERVED".equals(reservation.get().status())) {
            log.info("No active reservation to release for order {}", ctx.orderId());
            return;
        }
        for (var item : reservations.items(reservation.get().id())) { // already sorted by sku
            stock.release(item.sku(), item.quantity());
        }
        reservations.markReleased(reservation.get().id());
        write(ctx, "StockReleased", new InventoryEvents.StockReleased(reservation.get().id(), "PAYMENT_FAILED"));
        log.info("Released reservation {} for order {} (payment failed: {})",
                reservation.get().id(), ctx.orderId(), failed.reason());
    }

    private void write(EventContext ctx, String eventType, Object payload) {
        outbox.write(InventoryEvents.TOPIC, ctx.orderId(), ctx.correlationId(), eventType, EVENT_VERSION, payload);
    }
}
