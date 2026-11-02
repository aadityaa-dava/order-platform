package dev.aadityaa.orderplatform.platform.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.propagation.Propagator;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfiguration
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
public class MessagingAutoConfiguration {

    @Bean
    OutboxRepository outboxRepository(JdbcClient jdbc) {
        return new OutboxRepository(jdbc);
    }

    @Bean
    ProcessedEventRepository processedEventRepository(JdbcClient jdbc) {
        return new ProcessedEventRepository(jdbc);
    }

    @Bean
    OutboxWriter outboxWriter(OutboxRepository repository, ObjectMapper mapper,
                              @Value("${spring.application.name}") String producer,
                              ObjectProvider<MeterRegistry> registry,
                              ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        return new OutboxWriter(repository, mapper, producer, registry.getIfAvailable(SimpleMeterRegistry::new),
                tracer.getIfAvailable(), propagator.getIfAvailable());
    }

    /** Spring Boot applies a RecordInterceptor bean to every @KafkaListener container. */
    @Bean
    RecordInterceptor<Object, Object> logContextRecordInterceptor(ObjectMapper mapper) {
        return new LogContextRecordInterceptor(mapper);
    }

    /** Disable with outbox.publisher.enabled=false (e.g. in tests that do not need Kafka). */
    @Bean
    @ConditionalOnProperty(prefix = "outbox.publisher", name = "enabled", havingValue = "true", matchIfMissing = true)
    OutboxPublisher outboxPublisher(OutboxRepository repository, KafkaTemplate<String, String> kafka,
                                    TransactionTemplate tx, OutboxProperties props,
                                    ObjectProvider<MeterRegistry> registry,
                                    ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        return new OutboxPublisher(repository, kafka, tx, props, registry.getIfAvailable(SimpleMeterRegistry::new),
                tracer.getIfAvailable(), propagator.getIfAvailable());
    }

    /** Backlog and lag gauges; only where an outbox table exists (that is, where the publisher runs). */
    @Bean
    @ConditionalOnProperty(prefix = "outbox.publisher", name = "enabled", havingValue = "true", matchIfMissing = true)
    OutboxMetrics outboxMetrics(JdbcClient jdbc) {
        return new OutboxMetrics(jdbc);
    }
}
