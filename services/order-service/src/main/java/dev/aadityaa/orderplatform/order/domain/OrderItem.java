package dev.aadityaa.orderplatform.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(nullable = false, length = 64)
    private String sku;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_price_minor", nullable = false)
    private long unitPriceMinor;

    protected OrderItem() {
    }

    public OrderItem(String sku, int quantity, long unitPriceMinor) {
        this.sku = sku;
        this.quantity = quantity;
        this.unitPriceMinor = unitPriceMinor;
    }

    void setOrder(Order order) {
        this.order = order;
    }

    public String getSku() {
        return sku;
    }

    public int getQuantity() {
        return quantity;
    }

    public long getUnitPriceMinor() {
        return unitPriceMinor;
    }
}
