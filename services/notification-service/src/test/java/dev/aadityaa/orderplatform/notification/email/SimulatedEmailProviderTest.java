package dev.aadityaa.orderplatform.notification.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SimulatedEmailProviderTest {

    private EmailSettings settings;

    @BeforeEach
    void setUp() {
        settings = new EmailSettings();
        settings.setMinLatencyMs(0);
        settings.setMaxLatencyMs(0);
    }

    @Test
    void sameIdempotencyKeySendsOnlyOneEmail() {
        var provider = new SimulatedEmailProvider(settings, () -> 0.99);

        provider.send("notif-1", "a@example.com", "Subject", "Body");
        provider.send("notif-1", "a@example.com", "Subject", "Body");

        assertThat(provider.sentCount()).isEqualTo(1);
    }

    @Test
    void failureRateOfOneAlwaysFailsAndSendsNothing() {
        settings.setFailureRate(1.0);
        var provider = new SimulatedEmailProvider(settings, () -> 0.5);

        assertThatThrownBy(() -> provider.send("notif-2", "a@example.com", "S", "B"))
                .isInstanceOf(EmailProviderException.class);
        assertThat(provider.sentCount()).isZero();
    }

    @Test
    void failedSendCanBeRetriedWithTheSameKey() {
        settings.setFailureRate(0.5);
        AtomicInteger next = new AtomicInteger();
        double[] rolls = {0.0, 0.99}; // first call fails, second succeeds
        var provider = new SimulatedEmailProvider(settings, () -> rolls[next.getAndIncrement()]);

        assertThatThrownBy(() -> provider.send("notif-3", "a@example.com", "S", "B"))
                .isInstanceOf(EmailProviderException.class);
        provider.send("notif-3", "a@example.com", "S", "B");

        assertThat(provider.sentCount()).isEqualTo(1);
    }
}
