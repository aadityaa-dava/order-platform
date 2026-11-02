package dev.aadityaa.orderplatform.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class LogContextRecordInterceptorTest {

    private final LogContextRecordInterceptor interceptor = new LogContextRecordInterceptor(new ObjectMapper());

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    void putsTheEnvelopeIdsIntoTheLoggingContextAndRemovesThemAfterwards() {
        String json = "{\"eventId\":\"e-1\",\"eventType\":\"OrderCreated\",\"orderId\":\"o-1\",\"correlationId\":\"c-1\",\"payload\":{}}";
        var record = new ConsumerRecord<Object, Object>("order.events", 0, 0L, "o-1", json);

        interceptor.intercept(record, null);

        assertThat(MDC.get("correlationId")).isEqualTo("c-1");
        assertThat(MDC.get("orderId")).isEqualTo("o-1");
        assertThat(MDC.get("eventId")).isEqualTo("e-1");
        assertThat(MDC.get("eventType")).isEqualTo("OrderCreated");

        interceptor.afterRecord(record, null);

        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void aPoisonMessageStillGetsItsOrderIdFromTheRecordKey() {
        var record = new ConsumerRecord<Object, Object>("order.events", 0, 0L, "order-from-key", "this is not json");

        interceptor.intercept(record, null);

        assertThat(MDC.get("orderId")).isEqualTo("order-from-key");
        assertThat(MDC.get("correlationId")).isNull();
    }
}
