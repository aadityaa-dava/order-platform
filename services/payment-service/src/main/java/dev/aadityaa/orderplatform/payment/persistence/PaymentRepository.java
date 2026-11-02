package dev.aadityaa.orderplatform.payment.persistence;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PaymentRepository {

    private final JdbcClient jdbc;

    public PaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean existsByOrderId(UUID orderId) {
        return jdbc.sql("SELECT count(*) FROM payments WHERE order_id = :orderId")
                .param("orderId", orderId).query(Long.class).single() > 0;
    }

    /** Throws DataIntegrityViolationException if a payment for this order already exists (UNIQUE order_id). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(UUID id, UUID orderId, String customerId, long amountMinor, String currency,
                       String status, String failureReason, String gatewayReference, int attempts) {
        jdbc.sql("""
                INSERT INTO payments (id, order_id, customer_id, amount_minor, currency, status,
                                      failure_reason, gateway_reference, attempts)
                VALUES (:id, :orderId, :customerId, :amount, :currency, :status,
                        :failureReason, :gatewayReference, :attempts)
                """)
                .param("id", id)
                .param("orderId", orderId)
                .param("customerId", customerId)
                .param("amount", amountMinor)
                .param("currency", currency)
                .param("status", status)
                .param("failureReason", failureReason)
                .param("gatewayReference", gatewayReference)
                .param("attempts", attempts)
                .update();
    }
}
