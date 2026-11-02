package dev.aadityaa.orderplatform.testsupport;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/** Minimal Kafka client for tests: create the platform's topics, send a record, wait for a matching record. */
public class KafkaTestClient {

    public static final List<String> STANDARD_TOPICS = List.of("order.events", "inventory.events", "payment.events");

    private final String bootstrapServers;

    public KafkaTestClient(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    /** Same shape as docker-compose: 3 partitions, plus a dead-letter topic with matching partitions for each. */
    public void createStandardTopics() {
        List<NewTopic> topics = new ArrayList<>();
        for (String topic : STANDARD_TOPICS) {
            topics.add(new NewTopic(topic, 3, (short) 1));
            topics.add(new NewTopic(topic + ".DLT", 3, (short) 1));
        }
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", bootstrapServers))) {
            for (var result : admin.createTopics(topics).values().values()) {
                try {
                    result.get(30, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    if (!(e.getCause() instanceof TopicExistsException)) {
                        throw new IllegalStateException(e.getCause());
                    }
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    public void send(String topic, String key, String value) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(topic, key, value)).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Could not send to " + topic, e);
        }
    }

    /** Reads the topic from the beginning with a fresh consumer group until a record matches or time runs out. */
    public Optional<ConsumerRecord<String, String>> awaitRecord(String topic,
            Predicate<ConsumerRecord<String, String>> matcher, Duration timeout) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (matcher.test(record)) {
                        return Optional.of(record);
                    }
                }
            }
            return Optional.empty();
        }
    }

    public static String header(ConsumerRecord<?, ?> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
