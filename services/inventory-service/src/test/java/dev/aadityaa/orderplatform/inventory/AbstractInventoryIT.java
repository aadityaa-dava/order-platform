package dev.aadityaa.orderplatform.inventory;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for integration tests. One real PostgreSQL container per test JVM (singleton pattern):
 * Spring caches the application context across test classes, so the container must outlive any
 * single class. Kafka listeners and the outbox publisher are off so tests never touch the
 * developer's Kafka; they drive the service classes directly.
 */
@SpringBootTest(properties = {
        "spring.flyway.locations=classpath:db/migration",   // no demo seed data: each test creates its own stock
        "spring.kafka.listener.auto-startup=false",
        "outbox.publisher.enabled=false"
})
abstract class AbstractInventoryIT {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        postgres.start(); // removed automatically when the JVM exits
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
}
