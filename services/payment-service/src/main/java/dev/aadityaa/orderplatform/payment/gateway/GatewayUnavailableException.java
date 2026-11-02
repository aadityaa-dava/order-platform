package dev.aadityaa.orderplatform.payment.gateway;

/** Transient gateway failure (think HTTP 503): safe to retry. */
public class GatewayUnavailableException extends RuntimeException {
    public GatewayUnavailableException(String message) {
        super(message);
    }
}
