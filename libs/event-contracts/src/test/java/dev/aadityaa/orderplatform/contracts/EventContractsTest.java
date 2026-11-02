package dev.aadityaa.orderplatform.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class EventContractsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static java.util.List<String> eventTypes() {
        return EventContracts.EVENT_TYPES;
    }

    @ParameterizedTest
    @MethodSource("eventTypes")
    void everyExampleSatisfiesItsOwnSchema(String type) {
        assertThat(EventContracts.validate(type, EventContracts.example(type))).isEmpty();
    }

    // The schemas must also REJECT bad data; otherwise every later contract test passes for the wrong reason.

    @Test
    void missingEnvelopeFieldIsRejected() throws Exception {
        String broken = mutate("OrderCreated", event -> event.remove("orderId"));

        assertThat(EventContracts.validate("OrderCreated", broken)).anyMatch(m -> m.contains("orderId"));
    }

    @Test
    void missingPayloadFieldIsRejected() throws Exception {
        String broken = mutate("PaymentCompleted", event -> ((ObjectNode) event.get("payload")).remove("gatewayReference"));

        assertThat(EventContracts.validate("PaymentCompleted", broken)).anyMatch(m -> m.contains("gatewayReference"));
    }

    @Test
    void anEventOfTheWrongTypeIsRejected() {
        assertThat(EventContracts.validate("OrderCreated", EventContracts.example("OrderConfirmed"))).isNotEmpty();
    }

    @Test
    void invalidCurrencyIsRejected() throws Exception {
        String broken = mutate("OrderCreated", event -> ((ObjectNode) event.get("payload")).put("currency", "usd"));

        assertThat(EventContracts.validate("OrderCreated", broken)).isNotEmpty();
    }

    @Test
    void unknownFailureReasonIsRejected() throws Exception {
        String broken = mutate("PaymentFailed", event -> ((ObjectNode) event.get("payload")).put("reason", "MYSTERY"));

        assertThat(EventContracts.validate("PaymentFailed", broken)).isNotEmpty();
    }

    @Test
    void additiveChangesAreAllowed() throws Exception {
        // compatibility rule from docs/events.md: new optional fields must not break anyone
        String extended = mutate("OrderCreated", event -> {
            event.put("futureEnvelopeField", "x");
            ((ObjectNode) event.get("payload")).put("futurePayloadField", 42);
        });

        assertThat(EventContracts.validate("OrderCreated", extended)).isEmpty();
    }

    private static String mutate(String type, Consumer<ObjectNode> change) throws Exception {
        ObjectNode event = (ObjectNode) MAPPER.readTree(EventContracts.example(type));
        change.accept(event);
        return MAPPER.writeValueAsString(event);
    }
}
