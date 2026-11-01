package dev.aadityaa.orderplatform.inventory.reservation;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ReservationRepository {

    public record Reservation(UUID id, UUID orderId, String status) {
    }

    public record Item(String sku, int quantity) {
    }

    private final JdbcClient jdbc;

    public ReservationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean existsByOrderId(UUID orderId) {
        return jdbc.sql("SELECT count(*) FROM reservations WHERE order_id = :orderId")
                .param("orderId", orderId).query(Long.class).single() > 0;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(UUID reservationId, UUID orderId, Map<String, Integer> quantitiesBySku) {
        jdbc.sql("INSERT INTO reservations (id, order_id, status) VALUES (:id, :orderId, 'RESERVED')")
                .param("id", reservationId).param("orderId", orderId).update();
        quantitiesBySku.forEach((sku, qty) ->
                jdbc.sql("INSERT INTO reservation_items (reservation_id, sku, quantity) VALUES (:rid, :sku, :qty)")
                        .param("rid", reservationId).param("sku", sku).param("qty", qty).update());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Reservation> findByOrderIdForUpdate(UUID orderId) {
        return jdbc.sql("SELECT id, order_id, status FROM reservations WHERE order_id = :orderId FOR UPDATE")
                .param("orderId", orderId)
                .query((rs, n) -> new Reservation(
                        rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class), rs.getString("status")))
                .optional();
    }

    public List<Item> items(UUID reservationId) {
        return jdbc.sql("SELECT sku, quantity FROM reservation_items WHERE reservation_id = :rid ORDER BY sku")
                .param("rid", reservationId)
                .query((rs, n) -> new Item(rs.getString("sku"), rs.getInt("quantity")))
                .list();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markReleased(UUID reservationId) {
        jdbc.sql("UPDATE reservations SET status = 'RELEASED', updated_at = now() WHERE id = :id")
                .param("id", reservationId).update();
    }
}
