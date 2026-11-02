package dev.aadityaa.orderplatform.platform.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Spring Boot wires a CommonErrorHandler bean into every @KafkaListener container, so declaring it
 * here gives all services the same retry and dead-letter behaviour (and the same metrics).
 */
@AutoConfiguration
@EnableConfigurationProperties(ConsumerRetryProperties.class)
public class ConsumerErrorHandlingAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ConsumerErrorHandlingAutoConfiguration.class);

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafka, ConsumerRetryProperties props,
                                          ObjectProvider<MeterRegistry> meters) {
        MeterRegistry registry = meters.getIfAvailable(SimpleMeterRegistry::new);

        // Publishes to <original topic>.DLT on the same partition number, with exception details in headers.
        var deadLetters = new DeadLetterPublishingRecoverer(kafka,
                (record, ex) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
        ConsumerRecordRecoverer recoverer = (record, ex) -> {
            log.error("Dead-lettering {}-{}@{} after failure: {}",
                    record.topic(), record.partition(), record.offset(), rootMessage(ex));
            deadLetters.accept(record, ex);
            Counter.builder("consumer.dead_letters").description("Messages parked in a dead-letter topic")
                    .tag("topic", record.topic()).register(registry).increment();
        };

        var backOff = new ExponentialBackOff(props.initialIntervalMs(), props.multiplier());
        backOff.setMaxInterval(props.maxIntervalMs());
        backOff.setMaxAttempts(props.maxRetries());

        var handler = new DefaultErrorHandler(recoverer, backOff);
        // Poison messages: retrying cannot help, so skip the delay and park them immediately.
        handler.addNotRetryableExceptions(JsonProcessingException.class, IllegalArgumentException.class);
        handler.setRetryListeners((record, ex, attempt) -> {
            log.warn("Delivery attempt {} failed for {}-{}@{}: {}",
                    attempt, record.topic(), record.partition(), record.offset(), rootMessage(ex));
            Counter.builder("consumer.retries").description("Failed delivery attempts that will be retried or parked")
                    .tag("topic", record.topic()).register(registry).increment();
        });
        return handler;
    }

    private static String rootMessage(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
