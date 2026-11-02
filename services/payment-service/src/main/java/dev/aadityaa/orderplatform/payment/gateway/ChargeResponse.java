package dev.aadityaa.orderplatform.payment.gateway;

public record ChargeResponse(boolean approved, String reference, String declineReason) {
}
