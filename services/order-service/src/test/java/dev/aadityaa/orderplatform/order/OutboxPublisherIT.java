package dev.aadityaa.orderplatform.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aadityaa.orderplatform.order.api.CreateOrderRequest;
import dev.aadityaa.orderplatform.order.application.OrderService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The real polling publisher against a real Kafka broker (the publisher is enabled in this context). */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
class OutboxPublisherIT extends AbstractOrderIT {

    @Autowired OrderService orders;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void cleanTables() {
        jdbc.sql("TRUNCATE idempotency_keys, order_items, orders, outbox, processed_events CASCADE").update();
    }

    @Test
    void aCreatedOrderReachesKafkaWithItsKeyHeadersAndEnvelope() throws Exception {
        UUID id = newOrder();

        List<ConsumerRecord<String, String>> records = awaitRecords(Set.of(id.toString()), Duration.ofSeconds(30));

        assertThat(records).hasSize(1);
        ConsumerRecord<String, String> record = records.get(0);
        JsonNode json = mapper.readTree(record.value());
        assertThat(record.key()).isEqualTo(id.toString());
        assertThat(header(record, "eventType")).isEqualTo("OrderCreated");
        assertThat(header(record, "eventId")).isEqualTo(json.get("eventId").asText());
        assertThat(json.get("orderId").asText()).isEqualTo(id.toString());
        assertThat(json.get("producer").asText()).isEqualTo("order-service");
        assertThat(json.at("/payload/totalAmountMinor").asLong()).isEqualTo(2500);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(unpublishedCount()).isZero());
    }

    @Test
    void thirtyOrdersAreAllDeliveredAndNoneIsDuplicatedInANormalRun() {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 30; i++) {
            keys.add(newOrder().toString());
        }

        List<ConsumerRecord<String, String>> records = awaitRecords(keys, Duration.ofSeconds(45));

        assertThat(records).hasSize(30);
        assertThat(records.stream().map(r -> header(r, "eventId")).distinct()).hasSize(30);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(unpublishedCount()).isZero());
    }

    private UUID newOrder() {
        return orders.create("pub-" + UUID.randomUUID(), new CreateOrderRequest("cust-1", "jane@example.com", "USD",
                List.of(new CreateOrderRequest.Item("SKU-RED-MUG", 2, 1250)))).id();
    }

    private long unpublishedCount() {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE published_at IS NULL").query(Long.class).single();
    }

    /** Reads order.events until every key has been seen, then listens a little longer to catch duplicates. */
    private List<ConsumerRecord<String, String>> awaitRecords(Set<String> keys, Duration timeout) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of("order.events"));
            List<ConsumerRecord<String, String>> found = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            long deadline = System.nanoTime() + timeout.toNanos();
            while (seen.size() < keys.size() && System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (keys.contains(record.key())) {
                        found.add(record);
                        seen.add(record.key());
                    }
                }
            }
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofSeconds(2))) {
                if (keys.contains(record.key())) {
                    found.add(record);
                }
            }
            return found;
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}
