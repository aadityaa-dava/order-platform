package dev.aadityaa.orderplatform.payment.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Simulator and client settings. Fields are volatile because the admin endpoint changes them at runtime. */
@ConfigurationProperties(prefix = "payment.gateway")
public class GatewaySettings {

    private volatile long minLatencyMs = 50;
    private volatile long maxLatencyMs = 200;
    private volatile double declineRate = 0.0;
    private volatile double errorRate = 0.0;
    private volatile double timeoutRate = 0.0;
    private volatile long clientTimeoutMs = 2000;
    private volatile int maxAttempts = 3;
    private volatile long backoffMs = 200;

    public long getMinLatencyMs() { return minLatencyMs; }
    public void setMinLatencyMs(long v) { this.minLatencyMs = v; }
    public long getMaxLatencyMs() { return maxLatencyMs; }
    public void setMaxLatencyMs(long v) { this.maxLatencyMs = v; }
    public double getDeclineRate() { return declineRate; }
    public void setDeclineRate(double v) { this.declineRate = v; }
    public double getErrorRate() { return errorRate; }
    public void setErrorRate(double v) { this.errorRate = v; }
    public double getTimeoutRate() { return timeoutRate; }
    public void setTimeoutRate(double v) { this.timeoutRate = v; }
    public long getClientTimeoutMs() { return clientTimeoutMs; }
    public void setClientTimeoutMs(long v) { this.clientTimeoutMs = v; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int v) { this.maxAttempts = v; }
    public long getBackoffMs() { return backoffMs; }
    public void setBackoffMs(long v) { this.backoffMs = v; }
}
