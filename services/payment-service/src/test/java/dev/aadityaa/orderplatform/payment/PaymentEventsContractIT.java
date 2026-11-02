package dev.aadityaa.orderplatform.payment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aadityaa.orderplatform.contracts.EventContracts;
import dev.aadityaa.orderplatform.payment.application.PaymentService;
import dev.aadityaa.orderplatform.payment.events.InboundEvents;
import dev.aadityaa.orderplatform.payment.gateway.GatewaySettings;
import dev.aadityaa.orderplatform.platform.messaging.EventContext;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Producer contract: every event payment-service writes to its outbox must satisfy the shared schema. */
class PaymentEventsContractIT extends AbstractPaymentIT {

    @Autowired PaymentService service;
    @Autowired GatewaySettings settings;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE payments, outbox, processed_events").update();
        settings.setMinLatencyMs(0);
        settings.setMaxLatencyMs(0);
        settings.setDeclineRate(0);
        settings.setErrorRate(0);
        settings.setTimeoutRate(0);
        settings.setClientTimeoutMs(500);
        settings.setMaxAttempts(1);
        settings.setBackoffMs(1);
    }

    @Test
    void everyEventThePaymentServiceWritesHonoursTheContract() {
        service.onStockReserved(newContext(), reserved());                         // PaymentCompleted

        settings.setDeclineRate(1.0);
        service.onStockReserved(newContext(), reserved());                         // PaymentFailed: DECLINED

        settings.setDeclineRate(0);
        settings.setErrorRate(1.0);
        service.onStockReserved(newContext(), reserved());                         // PaymentFailed: GATEWAY_ERROR

        settings.setErrorRate(0);
        settings.setMinLatencyMs(300);                                             // slower than the 100 ms client timeout
        settings.setMaxLatencyMs(300);
        settings.setClientTimeoutMs(100);
        service.onStockReserved(newContext(), reserved());                         // PaymentFailed: GATEWAY_TIMEOUT

        List<Map.Entry<String, String>> rows = jdbc.sql("SELECT event_type, payload::text AS payload FROM outbox")
                .query((rs, n) -> Map.entry(rs.getString("event_type"), rs.getString("payload"))).list();
        Set<String> seen = new HashSet<>();
        for (var row : rows) {
            assertThat(EventContracts.validate(row.getKey(), row.getValue()))
                    .as("%s violates its contract: %s", row.getKey(), row.getValue()).isEmpty();
            seen.add(row.getKey());
        }
        assertThat(rows).hasSize(4);
        assertThat(seen).isEqualTo(Set.of("PaymentCompleted", "PaymentFailed"));
    }

    private static EventContext newContext() {
        return new EventContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    private static InboundEvents.StockReserved reserved() {
        return new InboundEvents.StockReserved("cust-1", 2500, "USD");
    }
}
