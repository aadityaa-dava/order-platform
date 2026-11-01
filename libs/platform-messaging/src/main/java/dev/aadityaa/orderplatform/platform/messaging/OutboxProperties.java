package dev.aadityaa.orderplatform.platform.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "outbox")
public record OutboxProperties(
        @DefaultValue("200") long pollIntervalMs,
        @DefaultValue("100") int batchSize,
        @DefaultValue("5000") long sendTimeoutMs,
        @DefaultValue("60000") long cleanupIntervalMs,
        @DefaultValue("3600") long cleanupRetentionSeconds) {
}
