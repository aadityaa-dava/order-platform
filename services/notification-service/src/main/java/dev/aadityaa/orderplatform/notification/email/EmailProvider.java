package dev.aadityaa.orderplatform.notification.email;

/** Port to the external email service. Implementations must honour the idempotency key. */
public interface EmailProvider {

    void send(String idempotencyKey, String to, String subject, String body);
}
