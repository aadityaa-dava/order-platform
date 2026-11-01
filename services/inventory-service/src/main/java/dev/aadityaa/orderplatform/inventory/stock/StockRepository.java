package dev.aadityaa.orderplatform.inventory.stock;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class StockRepository {

    private final JdbcClient jdbc;

    public StockRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Locks the rows in sorted sku order (consistent lock ordering prevents deadlocks between
     * concurrent orders) and returns available quantities. Unknown SKUs are simply absent.
     * MANDATORY: FOR UPDATE outside a transaction would release the lock immediately.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Integer> lockAndReadAvailable(Collection<String> skus) {
        return jdbc.sql("SELECT sku, available FROM stock WHERE sku IN (:skus) ORDER BY sku FOR UPDATE")
                .param("skus", skus)
                .query((rs, n) -> Map.entry(rs.getString("sku"), rs.getInt("available")))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** Moves units from available to reserved. The guard and the update-count check are defence in depth. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(String sku, int quantity) {
        int updated = jdbc.sql("""
                UPDATE stock
                SET available = available - :qty, reserved = reserved + :qty, updated_at = now()
                WHERE sku = :sku AND available >= :qty
                """)
                .param("qty", quantity).param("sku", sku).update();
        if (updated != 1) {
            throw new IllegalStateException("Stock invariant violated while reserving " + quantity + " of " + sku);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void release(String sku, int quantity) {
        int updated = jdbc.sql("""
                UPDATE stock
                SET available = available + :qty, reserved = reserved - :qty, updated_at = now()
                WHERE sku = :sku AND reserved >= :qty
                """)
                .param("qty", quantity).param("sku", sku).update();
        if (updated != 1) {
            throw new IllegalStateException("Stock invariant violated while releasing " + quantity + " of " + sku);
        }
    }
}
