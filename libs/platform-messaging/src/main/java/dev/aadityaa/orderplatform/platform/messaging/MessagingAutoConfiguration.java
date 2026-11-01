package dev.aadityaa.orderplatform.platform.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
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
                              @Value("${spring.application.name}") String producer) {
        return new OutboxWriter(repository, mapper, producer);
    }

    /** Disable with outbox.publisher.enabled=false (e.g. in tests that do not need Kafka). */
    @Bean
    @ConditionalOnProperty(prefix = "outbox.publisher", name = "enabled", havingValue = "true", matchIfMissing = true)
    OutboxPublisher outboxPublisher(OutboxRepository repository, KafkaTemplate<String, String> kafka,
                                    TransactionTemplate tx, OutboxProperties props) {
        return new OutboxPublisher(repository, kafka, tx, props);
    }
}
