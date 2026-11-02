package dev.aadityaa.orderplatform.order.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aadityaa.orderplatform.order.api.CreateOrderRequest;
import dev.aadityaa.orderplatform.order.api.OrderResponse;
import dev.aadityaa.orderplatform.order.domain.Order;
import dev.aadityaa.orderplatform.order.domain.OrderItem;
import dev.aadityaa.orderplatform.order.domain.OrderRepository;
import dev.aadityaa.orderplatform.order.idempotency.IdempotencyRepository;
import dev.aadityaa.orderplatform.order.outbox.OrderEventWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orders;
    private final IdempotencyRepository idempotency;
    private final OrderEventWriter events;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;

    public OrderService(OrderRepository orders, IdempotencyRepository idempotency,
                        OrderEventWriter events, TransactionTemplate tx, ObjectMapper mapper) {
        this.orders = orders;
        this.idempotency = idempotency;
        this.events = events;
        this.tx = tx;
        this.mapper = mapper;
    }

    /**
     * Not @Transactional on purpose: a duplicate-key failure marks a Spring transaction rollback-only,
     * so the transaction must end before we handle the conflict.
     */
    public OrderResponse create(String idempotencyKey, CreateOrderRequest request) {
        return create(idempotencyKey, request, UUID.randomUUID());
    }

    public OrderResponse create(String idempotencyKey, CreateOrderRequest request, UUID correlationId) {
        String hash = hash(request);

        Optional<OrderResponse> replay = replay(idempotencyKey, hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> persistNewOrder(idempotencyKey, hash, request, correlationId));
        } catch (DataIntegrityViolationException e) {
            // A concurrent request with the same key won the race; return its order.
            return replay(idempotencyKey, hash).orElseThrow(() -> e);
        }
    }

    @Transactional(readOnly = true)
    public OrderResponse get(UUID id) {
        return orders.findWithItemsById(id)
                .map(OrderResponse::from)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    private OrderResponse persistNewOrder(String key, String hash, CreateOrderRequest request, UUID correlationId) {
        var items = request.items().stream()
                .map(i -> new OrderItem(i.sku(), i.quantity(), i.unitPriceMinor()))
                .toList();
        Order order = Order.create(request.customerId(), request.customerEmail(),
                request.currency(), correlationId, items);

        orders.saveAndFlush(order);                    // order row must exist before the FK in idempotency_keys
        idempotency.insert(key, hash, order.getId());  // PK conflict here means a concurrent duplicate
        events.orderCreated(order);                    // outbox row, same transaction
        log.info("Order {} created: {} item(s), total {} {}", order.getId(), items.size(), order.getTotalAmountMinor(), order.getCurrency());
        return OrderResponse.from(order);
    }

    private Optional<OrderResponse> replay(String key, String hash) {
        return idempotency.find(key).map(entry -> {
            if (!entry.requestHash().equals(hash)) {
                throw new IdempotencyKeyReuseException(key);
            }
            return get(entry.orderId());
        });
    }

    private String hash(CreateOrderRequest request) {
        try {
            byte[] json = mapper.writeValueAsString(request).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Could not hash request", e);
        }
    }
}
