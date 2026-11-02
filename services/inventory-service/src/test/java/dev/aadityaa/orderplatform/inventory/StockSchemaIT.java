package dev.aadityaa.orderplatform.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

class StockSchemaIT extends AbstractInventoryIT {

    @Autowired
    JdbcClient jdbc;

    @Test
    void migrationsApplyAndStockRoundTrips() {
        jdbc.sql("INSERT INTO stock (sku, available) VALUES ('IT-SPIKE', 5)").update();

        Integer available = jdbc.sql("SELECT available FROM stock WHERE sku = 'IT-SPIKE'")
                .query(Integer.class).single();

        assertThat(available).isEqualTo(5);
    }

    @Test
    void databaseItselfRefusesNegativeStock() {
        // defence in depth: even if application logic had a bug, the CHECK constraint stops an oversell
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO stock (sku, available) VALUES ('IT-NEGATIVE', -1)").update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
