package dev.aadityaa.orderplatform.platform.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Defaults give retries after 1s, 2s, 4s, 8s (about 15s) before a message is dead-lettered. */
@ConfigurationProperties(prefix = "consumer.retry")
public record ConsumerRetryProperties(
        @DefaultValue("4") int maxRetries,
        @DefaultValue("1000") long initialIntervalMs,
        @DefaultValue("2.0") double multiplier,
        @DefaultValue("30000") long maxIntervalMs) {
}
