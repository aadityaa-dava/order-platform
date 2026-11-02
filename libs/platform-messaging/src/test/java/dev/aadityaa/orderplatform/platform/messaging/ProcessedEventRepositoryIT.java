package dev.aadityaa.orderplatform.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
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
class ProcessedEventRepositoryIT extends AbstractLibIT {

    @Autowired ProcessedEventRepository processed;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("TRUNCATE processed_events").update();
    }

    @Test
    void theFirstSightingWinsAndRepeatsAreReportedAsDuplicates() {
        UUID id = UUID.randomUUID();

        Boolean first = tx.execute(s -> processed.markProcessed(id));
        Boolean second = tx.execute(s -> processed.markProcessed(id));

        assertThat(first).isTrue();
        assertThat(second).isFalse();
    }

    @Test
    void markingOutsideATransactionIsRefused() {
        assertThatThrownBy(() -> processed.markProcessed(UUID.randomUUID()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void aRolledBackTransactionDoesNotLeaveAMarkerBehind() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            processed.markProcessed(id);
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        Boolean retry = tx.execute(s -> processed.markProcessed(id));
        assertThat(retry).isTrue(); // the retry is not mistaken for a duplicate
    }

    @Test
    void ofManyConcurrentCopiesExactlyOneWins() throws Exception {
        UUID id = UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return tx.execute(s -> processed.markProcessed(id));
            }));
        }
        start.countDown();
        int winners = 0;
        for (Future<Boolean> future : futures) {
            if (future.get(30, TimeUnit.SECONDS)) {
                winners++;
            }
        }
        pool.shutdown();

        assertThat(winners).isEqualTo(1);
    }
}
