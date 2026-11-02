package dev.aadityaa.orderplatform.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.aadityaa.orderplatform.contracts.EventContracts;
import dev.aadityaa.orderplatform.payment.events.InboundEvents;
import dev.aadityaa.orderplatform.platform.messaging.IncomingEvent;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/** Consumer contract: payment-service can read what inventory-service publishes. */
class PaymentConsumerContractTest {

    private static final ObjectMapper MAPPER = Jackson2ObjectMapperBuilder.json().build(); // same defaults as Spring Boot

    @Test
    void readsStockReserved() throws Exception {
        IncomingEvent event = IncomingEvent.parse(MAPPER, EventContracts.example("StockReserved"));
        var reserved = event.payloadAs(MAPPER, InboundEvents.StockReserved.class);

        assertThat(reserved.customerId()).isEqualTo("cust-1001");
        assertThat(reserved.totalAmountMinor()).isEqualTo(2500);
        assertThat(reserved.currency()).isEqualTo("USD");
    }

    @Test
    void toleratesFieldsAddedByNewerProducers() throws Exception {
        ObjectNode json = (ObjectNode) MAPPER.readTree(EventContracts.example("StockReserved"));
        json.put("futureEnvelopeField", "x");
        ((ObjectNode) json.get("payload")).put("futurePayloadField", 1);

        IncomingEvent event = IncomingEvent.parse(MAPPER, MAPPER.writeValueAsString(json));

        assertThat(event.payloadAs(MAPPER, InboundEvents.StockReserved.class).totalAmountMinor()).isEqualTo(2500);
    }
}
