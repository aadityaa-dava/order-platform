package dev.aadityaa.orderplatform.notification.email;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Volatile because the admin endpoint changes these at runtime. */
@ConfigurationProperties(prefix = "notification.email")
public class EmailSettings {

    private volatile double failureRate = 0.0;
    private volatile long minLatencyMs = 20;
    private volatile long maxLatencyMs = 100;

    public double getFailureRate() { return failureRate; }
    public void setFailureRate(double v) { this.failureRate = v; }
    public long getMinLatencyMs() { return minLatencyMs; }
    public void setMinLatencyMs(long v) { this.minLatencyMs = v; }
    public long getMaxLatencyMs() { return maxLatencyMs; }
    public void setMaxLatencyMs(long v) { this.maxLatencyMs = v; }
}
