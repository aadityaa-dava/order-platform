package dev.aadityaa.orderplatform.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(classes = LibTestApplication.class, properties = "outbox.publisher.enabled=false")
class OutboxRepositoryIT extends AbstractLibIT {

    @Autowired OutboxRepository outbox;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("TRUNCATE outbox").update();
    }

    @Test
    void insertOutsideATransactionIsRefused() {
        assertThatThrownBy(() -> outbox.insert(UUID.randomUUID(), UUID.randomUUID(), "T", "topic", "{}"))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(rowCount()).isZero();
    }

    @Test
    void insertCommitsWithTheSurroundingTransaction() {
        tx.executeWithoutResult(s -> outbox.insert(UUID.randomUUID(), UUID.randomUUID(), "T", "topic", "{\"a\":1}"));

        List<OutboxRecord> rows = outbox.lockUnpublished(10);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).payload()).contains("\"a\"");
        assertThat(rows.get(0).topic()).isEqualTo("topic");
    }

    @Test
    void aRolledBackTransactionTakesItsEventsWithIt() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            outbox.insert(UUID.randomUUID(), UUID.randomUUID(), "T", "topic", "{}");
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(rowCount()).isZero();
    }

    @Test
    void rowsComeBackOldestFirst() {
        tx.executeWithoutResult(s -> {
            outbox.insert(UUID.randomUUID(), UUID.randomUUID(), "A", "topic", "{}");
            outbox.insert(UUID.randomUUID(), UUID.randomUUID(), "B", "topic", "{}");
            outbox.insert(UUID.randomUUID(), UUID.randomUUID(), "C", "topic", "{}");
        });

        assertThat(outbox.lockUnpublished(10)).extracting(OutboxRecord::eventType).containsExactly("A", "B", "C");
        assertThat(outbox.lockUnpublished(2)).hasSize(2); // the batch limit is honoured
    }

    @Test
    void rowsLockedByOnePollerAreSkippedByAnother() throws Exception {
        tx.executeWithoutResult(s -> {
            outbox.insert(UUID.randomUUID(), UUID.randomUUID(), "A", "topic", "{}");
            outbox.insert(UUID.randomUUID(), UUID.randomUUID(), "B", "topic", "{}");
            outbox.insert(UUID.randomUUID(), UUID.randomUUID(), "C", "topic", "{}");
        });
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();

        Future<Integer> holder = pool.submit(() -> tx.execute(s -> {
            int claimed = outbox.lockUnpublished(10).size();
            locked.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return claimed;
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        List<OutboxRecord> whileLocked = tx.execute(s -> outbox.lockUnpublished(10)); // a second poller
        release.countDown();

        assertThat(holder.get(10, TimeUnit.SECONDS)).isEqualTo(3);
        assertThat(whileLocked).isEmpty(); // SKIP LOCKED: it did not wait and did not see the same rows
        pool.shutdown();
    }

    @Test
    void publishedRowsAreMarkedAndLaterCleanedUp() {
        tx.executeWithoutResult(s -> {
            outbox.insert(UUID.randomUUID(), UUID.randomUUID(), "A", "topic", "{}");
            outbox.insert(UUID.randomUUID(), UUID.randomUUID(), "B", "topic", "{}");
        });
        List<Long> ids = outbox.lockUnpublished(10).stream().map(OutboxRecord::id).toList();

        tx.executeWithoutResult(s -> outbox.markPublished(ids));

        assertThat(outbox.lockUnpublished(10)).isEmpty();
        assertThat(outbox.deletePublishedOlderThanSeconds(3600)).isZero(); // too recent to remove
        assertThat(outbox.deletePublishedOlderThanSeconds(0)).isEqualTo(2);
        outbox.markPublished(List.of()); // an empty batch is a harmless no-op
    }

    private long rowCount() {
        return jdbc.sql("SELECT count(*) FROM outbox").query(Long.class).single();
    }
}
