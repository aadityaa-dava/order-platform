package dev.aadityaa.orderplatform.payment.gateway;

public record ChargeRequest(String idempotencyKey, long amountMinor, String currency, String customerId) {
}
