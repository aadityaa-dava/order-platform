package dev.aadityaa.orderplatform.order;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Real PostgreSQL and a real Kafka broker, one of each per test JVM (singleton pattern).
 * Each IT class declares its own @SpringBootTest so it can choose its own properties.
 */
abstract class AbstractOrderIT {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    static final KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.1");

    static {
        postgres.start();
        kafka.start();
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }
}
