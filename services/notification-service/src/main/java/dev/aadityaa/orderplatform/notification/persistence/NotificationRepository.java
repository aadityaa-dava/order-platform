package dev.aadityaa.orderplatform.notification.persistence;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class NotificationRepository {

    private final JdbcClient jdbc;

    public NotificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean exists(UUID orderId, String type) {
        return jdbc.sql("SELECT count(*) FROM notifications WHERE order_id = :orderId AND type = :type")
                .param("orderId", orderId).param("type", type).query(Long.class).single() > 0;
    }

    /** UNIQUE (order_id, type) plus ON CONFLICT: a second notification for the same outcome is silently skipped. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void insertIfAbsent(UUID orderId, String type, String recipient, String subject, String body) {
        jdbc.sql("""
                INSERT INTO notifications (id, order_id, type, recipient, subject, body)
                VALUES (:id, :orderId, :type, :recipient, :subject, :body)
                ON CONFLICT (order_id, type) DO NOTHING
                """)
                .param("id", UUID.randomUUID())
                .param("orderId", orderId)
                .param("type", type)
                .param("recipient", recipient)
                .param("subject", subject)
                .param("body", body)
                .update();
    }
}
