package dev.aadityaa.orderplatform.payment.gateway;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Calls the gateway with a per-attempt timeout and bounded retries with exponential backoff.
 * Every attempt reuses the SAME idempotency key, so retrying an ambiguous timeout cannot double-charge.
 * Declines are final and never retried.
 */
@Component
public class PaymentGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentGatewayClient.class);

    private final PaymentGateway gateway;
    private final GatewaySettings settings;
    // Virtual threads: waiting on a slow gateway costs almost nothing.
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public PaymentGatewayClient(PaymentGateway gateway, GatewaySettings settings) {
        this.gateway = gateway;
        this.settings = settings;
    }

    public GatewayOutcome charge(String idempotencyKey, long amountMinor, String currency, String customerId) {
        var request = new ChargeRequest(idempotencyKey, amountMinor, currency, customerId);
        int maxAttempts = Math.max(1, settings.getMaxAttempts());
        String lastFailure = "GATEWAY_ERROR";

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Future<ChargeResponse> future = executor.submit(() -> gateway.charge(request));
            try {
                ChargeResponse response = future.get(settings.getClientTimeoutMs(), TimeUnit.MILLISECONDS);
                return response.approved()
                        ? GatewayOutcome.approved(response.reference(), attempt)
                        : GatewayOutcome.failed("DECLINED", attempt); // definitive: do not retry
            } catch (TimeoutException e) {
                future.cancel(true);
                lastFailure = "GATEWAY_TIMEOUT";
                log.warn("Gateway timeout on attempt {}/{} for key {}", attempt, maxAttempts, idempotencyKey);
            } catch (ExecutionException e) {
                lastFailure = "GATEWAY_ERROR";
                log.warn("Gateway error on attempt {}/{} for key {}: {}", attempt, maxAttempts, idempotencyKey, e.getCause().toString());
            } catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                // Shutting down is not a payment failure: let the message be redelivered instead.
                throw new IllegalStateException("Interrupted while calling the payment gateway", e);
            }
            if (attempt < maxAttempts) {
                backoff(attempt);
            }
        }
        return GatewayOutcome.failed(lastFailure, maxAttempts);
    }

    private void backoff(int attempt) {
        long delay = settings.getBackoffMs() * (1L << (attempt - 1));
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during gateway backoff", e);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
