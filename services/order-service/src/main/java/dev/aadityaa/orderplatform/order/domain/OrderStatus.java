package dev.aadityaa.orderplatform.order.domain;

public enum OrderStatus {
    PENDING,
    CONFIRMED,
    REJECTED,
    CANCELLED;

    public boolean isTerminal() {
        return this != PENDING;
    }
}
