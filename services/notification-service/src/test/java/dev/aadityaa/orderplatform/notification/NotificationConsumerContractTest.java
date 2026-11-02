package dev.aadityaa.orderplatform.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.aadityaa.orderplatform.contracts.EventContracts;
import dev.aadityaa.orderplatform.notification.events.InboundEvents;
import dev.aadityaa.orderplatform.platform.messaging.IncomingEvent;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/** Consumer contract: notification-service can read the order outcome events. */
class NotificationConsumerContractTest {

    private static final ObjectMapper MAPPER = Jackson2ObjectMapperBuilder.json().build(); // same defaults as Spring Boot

    @Test
    void readsOrderConfirmed() throws Exception {
        var confirmed = IncomingEvent.parse(MAPPER, EventContracts.example("OrderConfirmed"))
                .payloadAs(MAPPER, InboundEvents.OrderConfirmed.class);

        assertThat(confirmed.customerEmail()).isEqualTo("jane@example.com");
        assertThat(confirmed.totalAmountMinor()).isEqualTo(2500);
        assertThat(confirmed.currency()).isEqualTo("USD");
    }

    @Test
    void readsOrderRejected() throws Exception {
        var rejected = IncomingEvent.parse(MAPPER, EventContracts.example("OrderRejected"))
                .payloadAs(MAPPER, InboundEvents.OrderRejected.class);

        assertThat(rejected.customerEmail()).isEqualTo("jane@example.com");
        assertThat(rejected.reason()).isEqualTo("OUT_OF_STOCK");
    }

    @Test
    void readsOrderCancelled() throws Exception {
        var cancelled = IncomingEvent.parse(MAPPER, EventContracts.example("OrderCancelled"))
                .payloadAs(MAPPER, InboundEvents.OrderCancelled.class);

        assertThat(cancelled.customerEmail()).isEqualTo("jane@example.com");
        assertThat(cancelled.reason()).isEqualTo("DECLINED");
    }

    @Test
    void toleratesFieldsAddedByNewerProducers() throws Exception {
        ObjectNode json = (ObjectNode) MAPPER.readTree(EventContracts.example("OrderConfirmed"));
        json.put("futureEnvelopeField", "x");
        ((ObjectNode) json.get("payload")).put("futurePayloadField", 1);

        IncomingEvent event = IncomingEvent.parse(MAPPER, MAPPER.writeValueAsString(json));

        assertThat(event.payloadAs(MAPPER, InboundEvents.OrderConfirmed.class).customerEmail()).isEqualTo("jane@example.com");
    }
}
