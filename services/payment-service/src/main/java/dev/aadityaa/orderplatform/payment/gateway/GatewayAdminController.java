package dev.aadityaa.orderplatform.payment.gateway;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Lets failure experiments (Phase 11) change the simulated gateway without a restart.
 * Unauthenticated on purpose: this is a simulator control, not a production endpoint.
 */
@RestController
@RequestMapping("/admin/gateway")
public class GatewayAdminController {

    public record GatewayUpdate(Long minLatencyMs, Long maxLatencyMs, Double declineRate, Double errorRate,
                                Double timeoutRate, Long clientTimeoutMs, Integer maxAttempts, Long backoffMs) {
    }

    private final GatewaySettings settings;

    public GatewayAdminController(GatewaySettings settings) {
        this.settings = settings;
    }

    @GetMapping
    public GatewaySettings get() {
        return settings;
    }

    @PutMapping
    public GatewaySettings update(@RequestBody GatewayUpdate u) {
        double decline = u.declineRate() != null ? u.declineRate() : settings.getDeclineRate();
        double error = u.errorRate() != null ? u.errorRate() : settings.getErrorRate();
        double timeout = u.timeoutRate() != null ? u.timeoutRate() : settings.getTimeoutRate();
        long min = u.minLatencyMs() != null ? u.minLatencyMs() : settings.getMinLatencyMs();
        long max = u.maxLatencyMs() != null ? u.maxLatencyMs() : settings.getMaxLatencyMs();

        if (decline < 0 || error < 0 || timeout < 0 || decline + error + timeout > 1.0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rates must be >= 0 and sum to at most 1.0");
        }
        if (min < 0 || max < min) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Latency must satisfy 0 <= min <= max");
        }

        settings.setDeclineRate(decline);
        settings.setErrorRate(error);
        settings.setTimeoutRate(timeout);
        settings.setMinLatencyMs(min);
        settings.setMaxLatencyMs(max);
        if (u.clientTimeoutMs() != null) settings.setClientTimeoutMs(u.clientTimeoutMs());
        if (u.maxAttempts() != null) settings.setMaxAttempts(u.maxAttempts());
        if (u.backoffMs() != null) settings.setBackoffMs(u.backoffMs());
        return settings;
    }
}
