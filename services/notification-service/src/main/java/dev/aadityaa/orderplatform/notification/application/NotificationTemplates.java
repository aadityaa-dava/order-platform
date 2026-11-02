package dev.aadityaa.orderplatform.notification.application;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

/** Builds customer-facing messages from event data. */
public final class NotificationTemplates {

    public record Message(String subject, String body) {
    }

    private NotificationTemplates() {
    }

    /** Minor units to a display amount using the currency's own decimals: 2500 USD -> 25.00, 2500 JPY -> 2500. */
    public static String formatMoney(long amountMinor, String currency) {
        int digits;
        try {
            digits = Math.max(0, Currency.getInstance(currency).getDefaultFractionDigits());
        } catch (IllegalArgumentException e) {
            digits = 2;
        }
        return BigDecimal.valueOf(amountMinor, digits).toPlainString() + " " + currency;
    }

    public static String describeReason(String reason) {
        if (reason == null) {
            return "an unexpected problem occurred";
        }
        return switch (reason) {
            case "OUT_OF_STOCK" -> "one or more items are out of stock";
            case "UNKNOWN_SKU" -> "an item is no longer available";
            case "DECLINED" -> "your payment was declined";
            case "GATEWAY_TIMEOUT", "GATEWAY_ERROR" -> "our payment provider was unavailable";
            default -> "an unexpected problem occurred";
        };
    }

    public static Message confirmed(UUID orderId, long totalAmountMinor, String currency) {
        return new Message("Your order " + orderId + " is confirmed",
                "Thank you! Your order " + orderId + " is confirmed. We charged "
                        + formatMoney(totalAmountMinor, currency) + ".");
    }

    public static Message rejected(UUID orderId, String reason) {
        return new Message("We could not place your order " + orderId,
                "Sorry, we could not place your order " + orderId + " because " + describeReason(reason)
                        + ". You have not been charged.");
    }

    public static Message cancelled(UUID orderId, String reason) {
        return new Message("Your order " + orderId + " was cancelled",
                "Your order " + orderId + " was cancelled because " + describeReason(reason)
                        + ". Any reserved items have been released.");
    }
}
