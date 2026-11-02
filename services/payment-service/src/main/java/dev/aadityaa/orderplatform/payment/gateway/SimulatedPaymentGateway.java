package dev.aadityaa.orderplatform.payment.gateway;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Stand-in for a real provider. Like Stripe, it stores the first result per idempotency key and
 * returns it for every repeat of that key, so retries can never charge twice.
 */
@Component
public class SimulatedPaymentGateway implements PaymentGateway {

    private final GatewaySettings settings;
    private final DoubleSupplier outcomeRoll;
    private final ConcurrentMap<String, ChargeResponse> charges = new ConcurrentHashMap<>();

    @Autowired
    public SimulatedPaymentGateway(GatewaySettings settings) {
        this(settings, () -> ThreadLocalRandom.current().nextDouble());
    }

    /** Deterministic outcome source for tests. */
    SimulatedPaymentGateway(GatewaySettings settings, DoubleSupplier outcomeRoll) {
        this.settings = settings;
        this.outcomeRoll = outcomeRoll;
    }

    @Override
    public ChargeResponse charge(ChargeRequest request) throws InterruptedException {
        ChargeResponse previous = charges.get(request.idempotencyKey());
        sleepLatency();
        if (previous != null) {
            return previous; // replay: same key, same answer, no new charge
        }

        double roll = outcomeRoll.getAsDouble();
        double timeoutRate = settings.getTimeoutRate();
        double errorRate = settings.getErrorRate();
        double declineRate = settings.getDeclineRate();

        if (roll < timeoutRate) {
            // The charge goes through, but the response arrives too late: the caller cannot know.
            record(request, approved());
            Thread.sleep(settings.getClientTimeoutMs() * 2);
            return charges.get(request.idempotencyKey());
        }
        if (roll < timeoutRate + errorRate) {
            throw new GatewayUnavailableException("Simulated gateway failure (503)");
        }
        if (roll < timeoutRate + errorRate + declineRate) {
            return record(request, new ChargeResponse(false, null, "CARD_DECLINED"));
        }
        return record(request, approved());
    }

    /** How many charges actually succeeded; tests use it to prove retries never double-charge. */
    public long approvedChargeCount() {
        return charges.values().stream().filter(ChargeResponse::approved).count();
    }

    private ChargeResponse record(ChargeRequest request, ChargeResponse response) {
        // computeIfAbsent: if two calls with the same key race, exactly one outcome wins
        return charges.computeIfAbsent(request.idempotencyKey(), k -> response);
    }

    private static ChargeResponse approved() {
        return new ChargeResponse(true, "gw_" + UUID.randomUUID(), null);
    }

    private void sleepLatency() throws InterruptedException {
        long min = settings.getMinLatencyMs();
        long max = Math.max(min, settings.getMaxLatencyMs());
        long latency = min == max ? min : ThreadLocalRandom.current().nextLong(min, max + 1);
        if (latency > 0) {
            Thread.sleep(latency);
        }
    }
}
