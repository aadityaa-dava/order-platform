package dev.aadityaa.orderplatform.payment.gateway;

/** Final result of charging, after any retries. */
public record GatewayOutcome(boolean approved, String reference, String failureReason, int attempts) {

    public static GatewayOutcome approved(String reference, int attempts) {
        return new GatewayOutcome(true, reference, null, attempts);
    }

    public static GatewayOutcome failed(String reason, int attempts) {
        return new GatewayOutcome(false, null, reason, attempts);
    }
}
