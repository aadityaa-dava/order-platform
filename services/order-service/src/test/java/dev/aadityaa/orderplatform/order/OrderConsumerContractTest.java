package dev.aadityaa.orderplatform.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.aadityaa.orderplatform.contracts.EventContracts;
import dev.aadityaa.orderplatform.order.events.InboundEvents;
import dev.aadityaa.orderplatform.platform.messaging.IncomingEvent;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/** Consumer contract: order-service can read what inventory-service and payment-service publish. */
class OrderConsumerContractTest {

    private static final ObjectMapper MAPPER = Jackson2ObjectMapperBuilder.json().build(); // same defaults as Spring Boot

    @Test
    void readsStockReservationFailed() throws Exception {
        IncomingEvent event = IncomingEvent.parse(MAPPER, EventContracts.example("StockReservationFailed"));

        assertThat(event.eventType()).isEqualTo("StockReservationFailed");
        assertThat(event.payloadAs(MAPPER, InboundEvents.StockReservationFailed.class).reason()).isEqualTo("OUT_OF_STOCK");
    }

    @Test
    void readsPaymentCompleted() throws Exception {
        IncomingEvent event = IncomingEvent.parse(MAPPER, EventContracts.example("PaymentCompleted"));

        assertThat(event.payloadAs(MAPPER, InboundEvents.PaymentCompleted.class).gatewayReference()).isEqualTo("gw_abc123");
    }

    @Test
    void readsPaymentFailed() throws Exception {
        IncomingEvent event = IncomingEvent.parse(MAPPER, EventContracts.example("PaymentFailed"));

        assertThat(event.payloadAs(MAPPER, InboundEvents.PaymentFailed.class).reason()).isEqualTo("DECLINED");
    }

    @Test
    void toleratesFieldsAddedByNewerProducers() throws Exception {
        IncomingEvent event = IncomingEvent.parse(MAPPER, withExtraFields(EventContracts.example("PaymentFailed")));

        assertThat(event.payloadAs(MAPPER, InboundEvents.PaymentFailed.class).reason()).isEqualTo("DECLINED");
    }

    private static String withExtraFields(String json) throws Exception {
        ObjectNode event = (ObjectNode) MAPPER.readTree(json);
        event.put("futureEnvelopeField", "x");
        ((ObjectNode) event.get("payload")).put("futurePayloadField", 1);
        return MAPPER.writeValueAsString(event);
    }
}
