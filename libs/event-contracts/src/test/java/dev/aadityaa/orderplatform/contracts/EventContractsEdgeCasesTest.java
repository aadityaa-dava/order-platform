package dev.aadityaa.orderplatform.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EventContractsEdgeCasesTest {

    @Test
    void textThatIsNotJsonIsReportedAsAViolationNotThrown() {
        assertThat(EventContracts.validate("OrderCreated", "this is not json"))
                .singleElement().asString().startsWith("Not valid JSON");
    }

    @Test
    void anUnknownEventTypeIsAnError() {
        assertThatThrownBy(() -> EventContracts.validate("NoSuchEvent", "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NoSuchEvent");
        assertThatThrownBy(() -> EventContracts.example("NoSuchEvent"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
