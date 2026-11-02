package dev.aadityaa.orderplatform.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.aadityaa.orderplatform.testsupport.ExampleEvents;
import dev.aadityaa.orderplatform.testsupport.KafkaTestClient;
import dev.aadityaa.orderplatform.testsupport.SharedKafka;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The real Kafka listeners and the shared error handler, against a real broker and a real database. */
@SpringBootTest(properties = {
        "spring.flyway.locations=classpath:db/migration",
        "outbox.publisher.enabled=false",
        "consumer.retry.initial-interval-ms=50",   // keep retries quick in tests
        "consumer.retry.max-retries=2"
})
@DirtiesContext // closes the consumers afterwards so they cannot interfere with other test classes
class InventoryListenerIT extends AbstractInventoryIT {

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", SharedKafka::bootstrapServers);
    }

    @Autowired JdbcClient jdbc;

    private final KafkaTestClient kafka = new KafkaTestClient(SharedKafka.bootstrapServers());

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE outbox, processed_events, reservation_items, reservations, stock CASCADE").update();
        jdbc.sql("INSERT INTO stock (sku, available) VALUES ('L-1', 5)").update();
    }

    @Test
    void anOrderCreatedEventOnKafkaReservesTheStock() {
        var order = ExampleEvents.of("OrderCreated", payload -> reserveTwoOfL1(payload));

        kafka.send("order.events", order.orderId().toString(), order.json());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(reserved("L-1")).isEqualTo(2);
            assertThat(eventTypes(order.orderId())).containsExactly("StockReserved");
        });
    }

    @Test
    void aPoisonMessageIsParkedInTheDeadLetterTopicAndDoesNotBlockTheOrderBehindIt() {
        UUID orderId = UUID.randomUUID();
        String key = orderId.toString(); // same key, so both records sit in the same partition, one behind the other
        kafka.send("order.events", key, "this is not json");
        var order = ExampleEvents.of("OrderCreated", orderId, payload -> reserveTwoOfL1(payload));
        kafka.send("order.events", key, order.json());

        // the valid order behind the poison message is still processed: the partition was not blocked
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(reserved("L-1")).isEqualTo(2));

        // and the poison message was parked, not lost, with its origin and cause recorded
        var parked = kafka.awaitRecord("order.events.DLT", r -> "this is not json".equals(r.value()), Duration.ofSeconds(30));
        assertThat(parked).isPresent();
        ConsumerRecord<String, String> record = parked.get();
        assertThat(KafkaTestClient.header(record, "kafka_dlt-original-topic")).isEqualTo("order.events");
        assertThat(KafkaTestClient.header(record, "kafka_dlt-exception-cause-fqcn")).contains("Json");
    }

    private static void reserveTwoOfL1(ObjectNode payload) {
        ((ObjectNode) payload.get("items").get(0)).put("sku", "L-1").put("quantity", 2);
    }

    private int reserved(String sku) {
        return jdbc.sql("SELECT reserved FROM stock WHERE sku = :sku").param("sku", sku).query(Integer.class).single();
    }

    private List<String> eventTypes(UUID orderId) {
        return jdbc.sql("SELECT event_type FROM outbox WHERE aggregate_id = :id ORDER BY id").param("id", orderId)
                .query(String.class).list();
    }
}
