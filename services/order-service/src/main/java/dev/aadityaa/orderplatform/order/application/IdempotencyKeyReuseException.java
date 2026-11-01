package dev.aadityaa.orderplatform.order.application;

public class IdempotencyKeyReuseException extends RuntimeException {
    public IdempotencyKeyReuseException(String key) {
        super("Idempotency-Key '" + key + "' was already used with a different request body");
    }
}
