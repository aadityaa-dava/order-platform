package dev.aadityaa.orderplatform.notification.email;

/** Transient provider failure: the consumer's retry policy will try again. */
public class EmailProviderException extends RuntimeException {
    public EmailProviderException(String message) {
        super(message);
    }
}
