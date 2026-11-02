package dev.aadityaa.orderplatform.platform.messaging;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The outbox backlog and its lag: the first thing to grow when Kafka or the publisher has a problem. */
public class OutboxMetrics implements MeterBinder {

    private final JdbcClient jdbc;

    public OutboxMetrics(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("outbox.unpublished", this, OutboxMetrics::unpublished)
                .description("Events waiting in the outbox to be published").register(registry);
        Gauge.builder("outbox.oldest.age", this, OutboxMetrics::oldestAgeSeconds)
                .baseUnit("seconds").description("Age of the oldest event still waiting in the outbox").register(registry);
    }

    private double unpublished() {
        try {
            return jdbc.sql("SELECT count(*) FROM outbox WHERE published_at IS NULL").query(Long.class).single();
        } catch (Exception e) {
            return Double.NaN; // a metrics scrape must never fail because the database is busy
        }
    }

    private double oldestAgeSeconds() {
        try {
            return jdbc.sql("SELECT COALESCE(EXTRACT(EPOCH FROM (now() - min(created_at))), 0)::float8 FROM outbox WHERE published_at IS NULL")
                    .query(Double.class).single();
        } catch (Exception e) {
            return Double.NaN;
        }
    }
}
