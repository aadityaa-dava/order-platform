package dev.aadityaa.orderplatform.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.aadityaa.orderplatform.contracts.EventContracts;
import dev.aadityaa.orderplatform.inventory.events.InboundEvents;
import dev.aadityaa.orderplatform.platform.messaging.IncomingEvent;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/** Consumer contract: inventory-service can read what order-service and payment-service publish. */
class InventoryConsumerContractTest {

    private static final ObjectMapper MAPPER = Jackson2ObjectMapperBuilder.json().build(); // same defaults as Spring Boot

    @Test
    void readsOrderCreated() throws Exception {
        IncomingEvent event = IncomingEvent.parse(MAPPER, EventContracts.example("OrderCreated"));
        var order = event.payloadAs(MAPPER, InboundEvents.OrderCreated.class);

        assertThat(order.customerId()).isEqualTo("cust-1001");
        assertThat(order.items()).hasSize(1);
        assertThat(order.items().get(0).sku()).isEqualTo("SKU-RED-MUG");
        assertThat(order.items().get(0).quantity()).isEqualTo(2);
        assertThat(order.totalAmountMinor()).isEqualTo(2500);
        assertThat(order.currency()).isEqualTo("USD");
    }

    @Test
    void readsPaymentFailed() throws Exception {
        IncomingEvent event = IncomingEvent.parse(MAPPER, EventContracts.example("PaymentFailed"));

        assertThat(event.payloadAs(MAPPER, InboundEvents.PaymentFailed.class).reason()).isEqualTo("DECLINED");
    }

    @Test
    void toleratesFieldsAddedByNewerProducers() throws Exception {
        ObjectNode json = (ObjectNode) MAPPER.readTree(EventContracts.example("OrderCreated"));
        json.put("futureEnvelopeField", "x");
        ((ObjectNode) json.get("payload")).put("futurePayloadField", 1);

        IncomingEvent event = IncomingEvent.parse(MAPPER, MAPPER.writeValueAsString(json));

        assertThat(event.payloadAs(MAPPER, InboundEvents.OrderCreated.class).items()).hasSize(1);
    }
}
