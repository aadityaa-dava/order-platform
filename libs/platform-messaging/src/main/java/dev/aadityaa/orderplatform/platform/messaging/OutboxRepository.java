package dev.aadityaa.orderplatform.platform.messaging;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public class OutboxRepository {

    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * MANDATORY: must run inside the business transaction. Calling it without one throws,
     * so an event can never be written without the state change it describes (ADR-0002).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(UUID eventId, UUID aggregateId, String eventType, String topic, String payloadJson) {
        jdbc.sql("""
                INSERT INTO outbox (event_id, aggregate_id, event_type, topic, payload)
                VALUES (:eventId, :aggregateId, :eventType, :topic, CAST(:payload AS jsonb))
                """)
                .param("eventId", eventId)
                .param("aggregateId", aggregateId)
                .param("eventType", eventType)
                .param("topic", topic)
                .param("payload", payloadJson)
                .update();
    }

    /** Oldest unpublished rows; SKIP LOCKED lets concurrent pollers avoid blocking each other. */
    public List<OutboxRecord> lockUnpublished(int limit) {
        return jdbc.sql("""
                SELECT id, event_id, aggregate_id, event_type, topic, payload::text AS payload
                FROM outbox
                WHERE published_at IS NULL
                ORDER BY id
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
                """)
                .param("limit", limit)
                .query((rs, rowNum) -> new OutboxRecord(
                        rs.getLong("id"),
                        rs.getObject("event_id", UUID.class),
                        rs.getObject("aggregate_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("topic"),
                        rs.getString("payload")))
                .list();
    }

    public void markPublished(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.sql("UPDATE outbox SET published_at = now() WHERE id IN (:ids)")
                .param("ids", ids)
                .update();
    }

    public int deletePublishedOlderThanSeconds(long seconds) {
        return jdbc.sql("DELETE FROM outbox WHERE published_at IS NOT NULL AND published_at < now() - make_interval(secs => :secs)")
                .param("secs", seconds)
                .update();
    }
}
