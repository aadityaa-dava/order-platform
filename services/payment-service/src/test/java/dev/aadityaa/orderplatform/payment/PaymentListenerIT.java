package dev.aadityaa.orderplatform.payment;

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

/** The real Kafka listener drives payments, against a real broker and database. */
@SpringBootTest(properties = {
        "outbox.publisher.enabled=false",
        "consumer.retry.initial-interval-ms=50",
        "consumer.retry.max-retries=2"
})
@DirtiesContext
class PaymentListenerIT extends AbstractPaymentIT {

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", SharedKafka::bootstrapServers);
    }

    @Autowired JdbcClient jdbc;

    private final KafkaTestClient kafka = new KafkaTestClient(SharedKafka.bootstrapServers());

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE payments, outbox, processed_events").update();
    }

    @Test
    void stockReservedOnKafkaIsPaid() {
        var event = ExampleEvents.of("StockReserved", payload -> { });

        kafka.send("inventory.events", event.orderId().toString(), event.json());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(paymentStatus(event.orderId())).isEqualTo("COMPLETED");
            assertThat(eventTypes(event.orderId())).containsExactly("PaymentCompleted");
        });
    }

    @Test
    void theSameEventDeliveredTwiceIsPaidOnce() {
        var event = ExampleEvents.of("StockReserved", payload -> { });

        kafka.send("inventory.events", event.orderId().toString(), event.json());
        kafka.send("inventory.events", event.orderId().toString(), event.json()); // a redelivery of the very same event

        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(paymentCount(event.orderId())).isEqualTo(1);
            assertThat(eventTypes(event.orderId())).containsExactly("PaymentCompleted");
        });
    }

    private long paymentCount(UUID orderId) {
        return jdbc.sql("SELECT count(*) FROM payments WHERE order_id = :id").param("id", orderId).query(Long.class).single();
    }

    private String paymentStatus(UUID orderId) {
        return jdbc.sql("SELECT status FROM payments WHERE order_id = :id").param("id", orderId)
                .query(String.class).optional().orElse(null);
    }

    private List<String> eventTypes(UUID orderId) {
        return jdbc.sql("SELECT event_type FROM outbox WHERE aggregate_id = :id ORDER BY id").param("id", orderId)
                .query(String.class).list();
    }
}
