package dev.aadityaa.orderplatform.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PaymentGatewayClientTest {

    private GatewaySettings settings;

    @BeforeEach
    void setUp() {
        settings = new GatewaySettings();
        settings.setMinLatencyMs(0);
        settings.setMaxLatencyMs(0);
        settings.setClientTimeoutMs(100);
        settings.setMaxAttempts(3);
        settings.setBackoffMs(5);
    }

    private PaymentGatewayClient clientWithRolls(double... rolls) {
        AtomicInteger next = new AtomicInteger();
        var gateway = new SimulatedPaymentGateway(settings, () -> rolls[Math.min(next.getAndIncrement(), rolls.length - 1)]);
        return new PaymentGatewayClient(gateway, settings);
    }

    @Test
    void sameIdempotencyKeyChargesOnlyOnce() throws Exception {
        var gateway = new SimulatedPaymentGateway(settings, () -> 0.99);
        var request = new ChargeRequest("pay-1", 2500, "USD", "cust-1");

        ChargeResponse first = gateway.charge(request);
        ChargeResponse second = gateway.charge(request);

        assertThat(first.approved()).isTrue();
        assertThat(second.reference()).isEqualTo(first.reference());
        assertThat(gateway.approvedChargeCount()).isEqualTo(1);
    }

    @Test
    void declineIsFinalAndNotRetried() {
        settings.setDeclineRate(1.0);

        GatewayOutcome outcome = clientWithRolls(0.5).charge("pay-2", 100, "USD", "cust-1");

        assertThat(outcome.approved()).isFalse();
        assertThat(outcome.failureReason()).isEqualTo("DECLINED");
        assertThat(outcome.attempts()).isEqualTo(1);
    }

    @Test
    void transientErrorIsRetriedAndThenSucceeds() {
        settings.setErrorRate(0.5);

        // first roll 0.0 -> error, second roll 0.99 -> approved
        GatewayOutcome outcome = clientWithRolls(0.0, 0.99).charge("pay-3", 100, "USD", "cust-1");

        assertThat(outcome.approved()).isTrue();
        assertThat(outcome.attempts()).isEqualTo(2);
    }

    @Test
    void persistentErrorsExhaustAllAttempts() {
        settings.setErrorRate(1.0);

        GatewayOutcome outcome = clientWithRolls(0.5).charge("pay-4", 100, "USD", "cust-1");

        assertThat(outcome.approved()).isFalse();
        assertThat(outcome.failureReason()).isEqualTo("GATEWAY_ERROR");
        assertThat(outcome.attempts()).isEqualTo(3);
    }

    @Test
    void timeoutAfterChargeIsResolvedByRetryWithSameKeyAndNeverDoubleCharges() {
        settings.setTimeoutRate(1.0); // gateway charges, then responds too late

        var gateway = new SimulatedPaymentGateway(settings, () -> 0.5);
        var client = new PaymentGatewayClient(gateway, settings);
        GatewayOutcome outcome = client.charge("pay-5", 100, "USD", "cust-1");

        assertThat(outcome.approved()).isTrue();
        assertThat(outcome.attempts()).isEqualTo(2); // attempt 1 timed out, attempt 2 replayed the stored result
        assertThat(gateway.approvedChargeCount()).isEqualTo(1);
    }

    @Test
    void trulySlowGatewayExhaustsAttemptsAndFails() {
        settings.setMinLatencyMs(300); // always slower than the 100 ms client timeout
        settings.setMaxLatencyMs(300);
        settings.setMaxAttempts(2);

        var gateway = new SimulatedPaymentGateway(settings, () -> 0.99);
        var client = new PaymentGatewayClient(gateway, settings);
        GatewayOutcome outcome = client.charge("pay-6", 100, "USD", "cust-1");

        assertThat(outcome.approved()).isFalse();
        assertThat(outcome.failureReason()).isEqualTo("GATEWAY_TIMEOUT");
        assertThat(outcome.attempts()).isEqualTo(2);
        assertThat(gateway.approvedChargeCount()).isZero();
    }
}
