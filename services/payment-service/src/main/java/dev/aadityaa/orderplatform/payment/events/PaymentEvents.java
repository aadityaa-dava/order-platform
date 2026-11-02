package dev.aadityaa.orderplatform.payment.events;

import java.util.UUID;

/** Payloads of the events this service publishes on payment.events. */
public final class PaymentEvents {

    public static final String TOPIC = "payment.events";

    private PaymentEvents() {
    }

    public record PaymentCompleted(UUID paymentId, long totalAmountMinor, String currency, String gatewayReference) {
    }

    public record PaymentFailed(String reason, String detail) {
    }
}
