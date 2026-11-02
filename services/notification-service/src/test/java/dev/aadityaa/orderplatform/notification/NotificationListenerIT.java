package dev.aadityaa.orderplatform.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.aadityaa.orderplatform.testsupport.ExampleEvents;
import dev.aadityaa.orderplatform.testsupport.KafkaTestClient;
import dev.aadityaa.orderplatform.testsupport.SharedKafka;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The real Kafka listener sends notifications, against a real broker and database. */
@SpringBootTest(properties = {
        "consumer.retry.initial-interval-ms=50",
        "consumer.retry.max-retries=2"
})
@DirtiesContext
class NotificationListenerIT extends AbstractNotificationIT {

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", SharedKafka::bootstrapServers);
    }

    @Autowired JdbcClient jdbc;

    private final KafkaTestClient kafka = new KafkaTestClient(SharedKafka.bootstrapServers());

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE notifications, processed_events").update();
    }

    @Test
    void anOrderCreatedEventIsIgnoredAndTheConfirmationBehindItIsNotified() {
        UUID orderId = UUID.randomUUID();
        String key = orderId.toString(); // one partition, in order: created first, then confirmed
        kafka.send("order.events", key, ExampleEvents.of("OrderCreated", orderId, payload -> { }).json());
        kafka.send("order.events", key, ExampleEvents.of("OrderConfirmed", orderId, payload -> { }).json());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(notificationTypes(orderId)).containsExactly("ORDER_CONFIRMED"));
    }

    @Test
    void rejectedAndCancelledOutcomesAreNotifiedWithTheirOwnTypes() {
        UUID rejected = UUID.randomUUID();
        UUID cancelled = UUID.randomUUID();
        kafka.send("order.events", rejected.toString(), ExampleEvents.of("OrderRejected", rejected, payload -> { }).json());
        kafka.send("order.events", cancelled.toString(), ExampleEvents.of("OrderCancelled", cancelled, payload -> { }).json());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(notificationTypes(rejected)).containsExactly("ORDER_REJECTED");
            assertThat(notificationTypes(cancelled)).containsExactly("ORDER_CANCELLED");
        });
    }

    private List<String> notificationTypes(UUID orderId) {
        return jdbc.sql("SELECT type FROM notifications WHERE order_id = :id").param("id", orderId)
                .query(String.class).list();
    }
}
