package dev.aadityaa.orderplatform.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.aadityaa.orderplatform.order.api.CreateOrderRequest;
import dev.aadityaa.orderplatform.order.application.OrderService;
import dev.aadityaa.orderplatform.testsupport.ExampleEvents;
import dev.aadityaa.orderplatform.testsupport.KafkaTestClient;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;

/** The real Kafka listeners drive the order state machine, against a real broker and database. */
@SpringBootTest(properties = {
        "outbox.publisher.enabled=false",
        "consumer.retry.initial-interval-ms=50",
        "consumer.retry.max-retries=2"
})
@DirtiesContext
class OrderListenerIT extends AbstractOrderIT {

    static {
        new KafkaTestClient(kafka.getBootstrapServers()).createStandardTopics(); // before the listeners start
    }

    @Autowired OrderService orders;
    @Autowired JdbcClient jdbc;

    private final KafkaTestClient client = new KafkaTestClient(kafka.getBootstrapServers());

    @BeforeEach
    void cleanTables() {
        jdbc.sql("TRUNCATE idempotency_keys, order_items, orders, outbox, processed_events CASCADE").update();
    }

    @Test
    void paymentCompletedOnKafkaConfirmsTheOrder() {
        UUID id = newOrder();
        var event = ExampleEvents.of("PaymentCompleted", id, payload -> { });

        client.send("payment.events", id.toString(), event.json());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(orders.get(id).status()).isEqualTo("CONFIRMED");
            assertThat(eventTypes(id)).containsExactly("OrderCreated", "OrderConfirmed");
        });
    }

    @Test
    void stockReservationFailedOnKafkaRejectsTheOrder() {
        UUID id = newOrder();
        var event = ExampleEvents.of("StockReservationFailed", id, payload -> { });

        client.send("inventory.events", id.toString(), event.json());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(orders.get(id).status()).isEqualTo("REJECTED");
            assertThat(orders.get(id).failureReason()).isEqualTo("OUT_OF_STOCK");
        });
    }

    @Test
    void paymentFailedOnKafkaCancelsTheOrder() {
        UUID id = newOrder();
        var event = ExampleEvents.of("PaymentFailed", id, payload -> { });

        client.send("payment.events", id.toString(), event.json());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(orders.get(id).status()).isEqualTo("CANCELLED");
            assertThat(orders.get(id).failureReason()).isEqualTo("DECLINED");
        });
    }

    @Test
    void aPaymentEventForAnUnknownOrderIsRetriedThenDeadLettered() {
        UUID ghost = UUID.randomUUID();
        var event = ExampleEvents.of("PaymentCompleted", ghost, payload -> { });

        client.send("payment.events", ghost.toString(), event.json());

        var parked = client.awaitRecord("payment.events.DLT", r -> r.value().contains(ghost.toString()), Duration.ofSeconds(30));
        assertThat(parked).isPresent();
        assertThat(KafkaTestClient.header(parked.get(), "kafka_dlt-original-topic")).isEqualTo("payment.events");
        assertThat(KafkaTestClient.header(parked.get(), "kafka_dlt-exception-cause-fqcn")).contains("IllegalStateException");
        // each failed attempt rolled back its processed-event marker, so a replay after a fix is not mistaken for a duplicate
        assertThat(jdbc.sql("SELECT count(*) FROM processed_events").query(Long.class).single()).isZero();
    }

    private UUID newOrder() {
        return orders.create("listener-" + UUID.randomUUID(), new CreateOrderRequest("cust-1", "jane@example.com", "USD",
                List.of(new CreateOrderRequest.Item("SKU-RED-MUG", 2, 1250)))).id();
    }

    private List<String> eventTypes(UUID id) {
        return jdbc.sql("SELECT event_type FROM outbox WHERE aggregate_id = :id ORDER BY id").param("id", id)
                .query(String.class).list();
    }
}
