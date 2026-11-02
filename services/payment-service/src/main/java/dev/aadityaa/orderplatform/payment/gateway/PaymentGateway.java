package dev.aadityaa.orderplatform.payment.gateway;

/** Port to the external payment provider. Implementations must honour the idempotency key. */
public interface PaymentGateway {

    ChargeResponse charge(ChargeRequest request) throws Exception;
}
