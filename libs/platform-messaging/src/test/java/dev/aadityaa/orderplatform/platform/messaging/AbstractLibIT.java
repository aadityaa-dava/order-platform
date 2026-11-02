package dev.aadityaa.orderplatform.platform.messaging;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/** One PostgreSQL per JVM; Kafka is started lazily, only by the tests that need it. */
abstract class AbstractLibIT {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    private static KafkaContainer kafka;

    static {
        postgres.start();
    }

    static synchronized KafkaContainer kafka() {
        if (kafka == null) {
            kafka = new KafkaContainer("apache/kafka:3.9.1");
            kafka.start();
        }
        return kafka;
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
}
