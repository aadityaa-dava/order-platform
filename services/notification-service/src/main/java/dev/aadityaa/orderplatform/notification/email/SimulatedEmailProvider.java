package dev.aadityaa.orderplatform.notification.email;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Stand-in for a real email provider: fails at a configurable rate and ignores repeated idempotency keys. */
@Component
public class SimulatedEmailProvider implements EmailProvider {

    private final EmailSettings settings;
    private final DoubleSupplier roll;
    private final ConcurrentMap<String, String> sent = new ConcurrentHashMap<>();

    @Autowired
    public SimulatedEmailProvider(EmailSettings settings) {
        this(settings, () -> ThreadLocalRandom.current().nextDouble());
    }

    /** Deterministic outcome source for tests. */
    SimulatedEmailProvider(EmailSettings settings, DoubleSupplier roll) {
        this.settings = settings;
        this.roll = roll;
    }

    @Override
    public void send(String idempotencyKey, String to, String subject, String body) {
        sleepLatency();
        if (sent.containsKey(idempotencyKey)) {
            return; // same key: already sent, do not send a second email
        }
        if (roll.getAsDouble() < settings.getFailureRate()) {
            throw new EmailProviderException("Simulated email provider outage");
        }
        sent.putIfAbsent(idempotencyKey, to + " | " + subject);
    }

    /** Emails actually delivered; lets tests and the admin endpoint prove nobody got a duplicate. */
    public int sentCount() {
        return sent.size();
    }

    private void sleepLatency() {
        long min = settings.getMinLatencyMs();
        long max = Math.max(min, settings.getMaxLatencyMs());
        long latency = min == max ? min : ThreadLocalRandom.current().nextLong(min, max + 1);
        if (latency > 0) {
            try {
                Thread.sleep(latency);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while sending email", e);
            }
        }
    }
}
