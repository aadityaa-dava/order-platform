package dev.aadityaa.orderplatform.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

/** The publisher with an unreachable broker: events must wait in the outbox, never be lost or marked as sent. */
@SpringBootTest(classes = LibTestApplication.class, properties = {
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.producer.properties.max.block.ms=300",
        "spring.kafka.producer.properties.request.timeout.ms=300",
        "spring.kafka.producer.properties.delivery.timeout.ms=600",
        "outbox.send-timeout-ms=1000"
})
@DirtiesContext
class OutboxPublisherBrokerDownIT extends AbstractLibIT {

    @Autowired OutboxWriter writer;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("TRUNCATE outbox").update();
    }

    @Test
    void eventsStayInTheOutboxWhileTheBrokerIsUnreachable() {
        for (int i = 0; i < 3; i++) {
            final int seq = i;
            tx.executeWithoutResult(s -> writer.write("lib-it-down", UUID.randomUUID(), UUID.randomUUID(), "TestEvent", 1, Map.of("seq", seq)));
        }

        // several publish cycles run and fail during this window; nothing may be marked published or deleted
        await().during(Duration.ofSeconds(4)).atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(unpublished()).isEqualTo(3));
    }

    private long unpublished() {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE published_at IS NULL").query(Long.class).single();
    }
}
