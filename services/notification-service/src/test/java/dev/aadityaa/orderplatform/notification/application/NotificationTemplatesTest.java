package dev.aadityaa.orderplatform.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationTemplatesTest {

    @Test
    void formatsMoneyUsingTheCurrencysOwnDecimals() {
        assertThat(NotificationTemplates.formatMoney(2500, "USD")).isEqualTo("25.00 USD");
        assertThat(NotificationTemplates.formatMoney(2500, "JPY")).isEqualTo("2500 JPY"); // no minor unit
        assertThat(NotificationTemplates.formatMoney(2500, "KWD")).isEqualTo("2.500 KWD"); // three decimals
    }

    @Test
    void unknownCurrencyFallsBackToTwoDecimals() {
        assertThat(NotificationTemplates.formatMoney(1999, "ZZZ")).isEqualTo("19.99 ZZZ");
    }

    @Test
    void confirmedMessageMentionsOrderAndAmount() {
        UUID id = UUID.randomUUID();
        var message = NotificationTemplates.confirmed(id, 2500, "USD");

        assertThat(message.subject()).contains(id.toString());
        assertThat(message.body()).contains("25.00 USD");
    }

    @Test
    void failureReasonsBecomeCustomerFriendlyText() {
        UUID id = UUID.randomUUID();

        assertThat(NotificationTemplates.rejected(id, "OUT_OF_STOCK").body()).contains("out of stock");
        assertThat(NotificationTemplates.cancelled(id, "DECLINED").body()).contains("declined");
        assertThat(NotificationTemplates.cancelled(id, "SOMETHING_NEW").body()).contains("unexpected problem");
        assertThat(NotificationTemplates.cancelled(id, null).body()).contains("unexpected problem");
    }
}
