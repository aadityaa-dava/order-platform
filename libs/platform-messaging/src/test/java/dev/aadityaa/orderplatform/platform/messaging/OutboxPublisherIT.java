package dev.aadityaa.orderplatform.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/** The real polling publisher against a real Kafka broker. */
@SpringBootTest(classes = LibTestApplication.class)
@DirtiesContext // closes this context (and its scheduler) afterwards so it cannot publish other tests' rows
class OutboxPublisherIT extends AbstractLibIT {

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", () -> kafka().getBootstrapServers());
    }

    @Autowired OutboxWriter writer;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void clean() {
        jdbc.sql("TRUNCATE outbox").update();
    }

    @Test
    void publishesWithTheOrderIdAsKeyTheHeadersAndTheEnvelope() throws Exception {
        String topic = "lib-it-" + UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        tx.executeWithoutResult(s -> writer.write(topic, orderId, UUID.randomUUID(), "TestEvent", 1, Map.of("seq", 7)));

        List<ConsumerRecord<String, String>> records = readTopic(topic, 1);

        assertThat(records).hasSize(1);
        ConsumerRecord<String, String> record = records.get(0);
        JsonNode json = mapper.readTree(record.value());
        assertThat(record.key()).isEqualTo(orderId.toString());
        assertThat(header(record, "eventType")).isEqualTo("TestEvent");
        assertThat(header(record, "eventId")).isEqualTo(json.get("eventId").asText());
        assertThat(json.get("producer").asText()).isEqualTo("lib-test");
        assertThat(json.get("orderId").asText()).isEqualTo(orderId.toString());
        assertThat(json.at("/payload/seq").asInt()).isEqualTo(7);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(unpublished()).isZero());
    }

    @Test
    void eventsOfOneOrderArriveInTheOrderTheyWereWritten() throws Exception {
        String topic = "lib-it-" + UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        for (int seq = 0; seq < 25; seq++) { // separate transactions, so the publisher sees several batches
            final int value = seq;
            tx.executeWithoutResult(s -> writer.write(topic, orderId, UUID.randomUUID(), "TestEvent", 1, Map.of("seq", value)));
        }

        List<ConsumerRecord<String, String>> records = readTopic(topic, 25);

        assertThat(records).hasSize(25); // all delivered, none duplicated in a normal run
        List<Integer> sequence = new ArrayList<>();
        for (ConsumerRecord<String, String> record : records) {
            sequence.add(mapper.readTree(record.value()).at("/payload/seq").asInt());
        }
        assertThat(sequence).isSorted().first().isEqualTo(0);
    }

    private long unpublished() {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE published_at IS NULL").query(Long.class).single();
    }

    private List<ConsumerRecord<String, String>> readTopic(String topic, int expected) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka().getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            List<ConsumerRecord<String, String>> found = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
            while (found.size() < expected && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(found::add);
            }
            consumer.poll(Duration.ofSeconds(2)).forEach(found::add); // listen a little longer to catch duplicates
            return found;
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}
