package dev.aadityaa.orderplatform.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(classes = LibTestApplication.class, properties = "outbox.publisher.enabled=false")
class OutboxMetricsIT extends AbstractLibIT {

    @Autowired OutboxRepository outbox;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("TRUNCATE outbox").update();
    }

    @Test
    void gaugesTrackTheBacklogAndItsAge() {
        var registry = new SimpleMeterRegistry();
        new OutboxMetrics(jdbc).bindTo(registry);
        assertThat(registry.get("outbox.unpublished").gauge().value()).isZero();
        assertThat(registry.get("outbox.oldest.age").gauge().value()).isZero();

        tx.executeWithoutResult(s -> {
            outbox.insert(UUID.randomUUID(), UUID.randomUUID(), "A", "topic", "{}");
            outbox.insert(UUID.randomUUID(), UUID.randomUUID(), "B", "topic", "{}");
        });
        jdbc.sql("UPDATE outbox SET created_at = now() - interval '30 seconds'").update(); // pretend they have waited

        assertThat(registry.get("outbox.unpublished").gauge().value()).isEqualTo(2);
        assertThat(registry.get("outbox.oldest.age").gauge().value()).isGreaterThanOrEqualTo(30);

        List<Long> ids = outbox.lockUnpublished(10).stream().map(OutboxRecord::id).toList();
        tx.executeWithoutResult(s -> outbox.markPublished(ids));

        assertThat(registry.get("outbox.unpublished").gauge().value()).isZero(); // drained: backlog and lag are back to 0
        assertThat(registry.get("outbox.oldest.age").gauge().value()).isZero();
    }
}
