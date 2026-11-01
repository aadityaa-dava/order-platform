package dev.aadityaa.orderplatform.platform.messaging;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Consumer-side deduplication (ADR-0003). */
public class ProcessedEventRepository {

    private final JdbcClient jdbc;

    public ProcessedEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records the event id in the CALLER's transaction.
     *
     * @return true if this is the first time the event is seen; false if it is a duplicate.
     *         Concurrent duplicates serialize on the primary key: the second waits for the first
     *         to commit, then inserts nothing.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markProcessed(UUID eventId) {
        return jdbc.sql("INSERT INTO processed_events (event_id) VALUES (:id) ON CONFLICT (event_id) DO NOTHING")
                .param("id", eventId)
                .update() == 1;
    }
}
