package dev.aadityaa.orderplatform.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aadityaa.orderplatform.testsupport.KafkaTestClient;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The shared retry and dead-letter handler, exercised by a deliberately failing listener on a real broker. */
@SpringBootTest(classes = {LibTestApplication.class, ConsumerErrorHandlingIT.FailingListener.class}, properties = {
        "outbox.publisher.enabled=false",
        "consumer.retry.initial-interval-ms=50",
        "consumer.retry.max-retries=2",
        "spring.kafka.consumer.group-id=lib-error-it",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.listener.ack-mode=record"
})
@DirtiesContext
class ConsumerErrorHandlingIT extends AbstractLibIT {

    private static final String TOPIC = "lib-it-errors";

    static {
        // create the topic before the listener subscribes, so it does not wait for a metadata refresh
        new KafkaTestClient(kafka().getBootstrapServers()).send(TOPIC, "warmup", "ok");
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", () -> kafka().getBootstrapServers());
    }

    @Component
    static class FailingListener {
        static final Map<String, AtomicInteger> ATTEMPTS = new ConcurrentHashMap<>();

        @KafkaListener(topics = TOPIC)
        void on(String value) {
            ATTEMPTS.computeIfAbsent(value, v -> new AtomicInteger()).incrementAndGet();
            if (value.startsWith("poison")) {
                throw new IllegalArgumentException("cannot ever succeed");
            }
            if (value.startsWith("transient")) {
                throw new IllegalStateException("might succeed later");
            }
        }
    }

    private final KafkaTestClient client = new KafkaTestClient(kafka().getBootstrapServers());

    @Test
    void aPoisonMessageIsDeadLetteredAfterExactlyOneAttempt() {
        String value = "poison-" + UUID.randomUUID();
        client.send(TOPIC, "k", value);

        var parked = client.awaitRecord(TOPIC + ".DLT", r -> value.equals(r.value()), Duration.ofSeconds(30));

        assertThat(parked).isPresent();
        assertThat(KafkaTestClient.header(parked.get(), "kafka_dlt-original-topic")).isEqualTo(TOPIC);
        assertThat(KafkaTestClient.header(parked.get(), "kafka_dlt-exception-cause-fqcn")).contains("IllegalArgumentException");
        assertThat(FailingListener.ATTEMPTS.get(value).get()).isEqualTo(1); // never retried
    }

    @Test
    void aTransientFailureIsRetriedThenDeadLettered() {
        String value = "transient-" + UUID.randomUUID();
        client.send(TOPIC, "k", value);

        var parked = client.awaitRecord(TOPIC + ".DLT", r -> value.equals(r.value()), Duration.ofSeconds(30));

        assertThat(parked).isPresent();
        assertThat(KafkaTestClient.header(parked.get(), "kafka_dlt-exception-cause-fqcn")).contains("IllegalStateException");
        assertThat(FailingListener.ATTEMPTS.get(value).get()).isEqualTo(3); // the first delivery plus 2 retries
    }
}
