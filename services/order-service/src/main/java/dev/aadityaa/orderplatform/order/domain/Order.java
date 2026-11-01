package dev.aadityaa.orderplatform.order.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    private UUID id;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Column(name = "customer_email", nullable = false)
    private String customerEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OrderStatus status;

    @Column(name = "total_amount_minor", nullable = false)
    private long totalAmountMinor;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "correlation_id", nullable = false)
    private UUID correlationId;

    @Column(name = "failure_reason")
    private String failureReason;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {
    }

    public static Order create(String customerId, String customerEmail, String currency,
                               UUID correlationId, List<OrderItem> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("An order needs at least one item");
        }
        Order order = new Order();
        order.id = UUID.randomUUID();
        order.customerId = customerId;
        order.customerEmail = customerEmail;
        order.currency = currency;
        order.correlationId = correlationId;
        order.status = OrderStatus.PENDING;
        order.createdAt = Instant.now();
        order.updatedAt = order.createdAt;

        long total = 0;
        for (OrderItem item : items) {
            item.setOrder(order);
            order.items.add(item);
            // exact arithmetic: overflow throws instead of silently wrapping
            total = Math.addExact(total, Math.multiplyExact(item.getUnitPriceMinor(), (long) item.getQuantity()));
        }
        order.totalAmountMinor = total;
        return order;
    }

    /** @return true if the transition happened; false if the order was already terminal (event ignored). */
    public boolean confirm() {
        return transitionTo(OrderStatus.CONFIRMED, null);
    }

    public boolean reject(String reason) {
        return transitionTo(OrderStatus.REJECTED, reason);
    }

    public boolean cancel(String reason) {
        return transitionTo(OrderStatus.CANCELLED, reason);
    }

    private boolean transitionTo(OrderStatus target, String reason) {
        if (status.isTerminal()) {
            return false;
        }
        this.status = target;
        this.failureReason = reason;
        this.updatedAt = Instant.now();
        return true;
    }

    public UUID getId() {
        return id;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public long getTotalAmountMinor() {
        return totalAmountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public UUID getCorrelationId() {
        return correlationId;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }
}
