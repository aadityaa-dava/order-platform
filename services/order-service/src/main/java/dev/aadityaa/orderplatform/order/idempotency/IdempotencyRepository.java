package dev.aadityaa.orderplatform.order.idempotency;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class IdempotencyRepository {

    public record Entry(String requestHash, UUID orderId) {
    }

    private final JdbcClient jdbc;

    public IdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Entry> find(String key) {
        return jdbc.sql("SELECT request_hash, order_id FROM idempotency_keys WHERE idempotency_key = :key")
                .param("key", key)
                .query((rs, n) -> new Entry(rs.getString("request_hash").trim(), rs.getObject("order_id", UUID.class)))
                .optional();
    }

    /** Throws DataIntegrityViolationException if the key already exists (primary key). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(String key, String requestHash, UUID orderId) {
        jdbc.sql("INSERT INTO idempotency_keys (idempotency_key, request_hash, order_id) VALUES (:key, :hash, :orderId)")
                .param("key", key)
                .param("hash", requestHash)
                .param("orderId", orderId)
                .update();
    }
}
