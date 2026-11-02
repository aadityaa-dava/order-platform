package dev.aadityaa.orderplatform.testsupport;

import org.testcontainers.kafka.KafkaContainer;

/** One Kafka broker per test JVM, started on first use, with the platform's standard topics already created. */
public final class SharedKafka {

    private static KafkaContainer container;

    private SharedKafka() {
    }

    public static synchronized String bootstrapServers() {
        if (container == null) {
            container = new KafkaContainer("apache/kafka:3.9.1");
            container.start(); // removed automatically when the JVM exits
            new KafkaTestClient(container.getBootstrapServers()).createStandardTopics();
        }
        return container.getBootstrapServers();
    }
}
