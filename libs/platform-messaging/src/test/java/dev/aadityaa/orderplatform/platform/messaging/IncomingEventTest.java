package dev.aadityaa.orderplatform.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

class IncomingEventTest {

    private static final ObjectMapper MAPPER = Jackson2ObjectMapperBuilder.json().build(); // Spring Boot defaults

    private static final String VALID = """
            {"eventId":"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee","eventType":"PaymentFailed","eventVersion":1,
             "orderId":"b2a1c3d4-5e6f-4789-a0b1-c2d3e4f50617","correlationId":"0c9d8e7f-6a5b-4c3d-8e1f-0a9b8c7d6e5f",
             "payload":{"reason":"DECLINED","somethingNew":true}}""";

    record Failed(String reason) {
    }

    @Test
    void parsesTheEnvelope() throws Exception {
        IncomingEvent event = IncomingEvent.parse(MAPPER, VALID);

        assertThat(event.eventType()).isEqualTo("PaymentFailed");
        assertThat(event.context().eventId().toString()).isEqualTo("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
        assertThat(event.context().orderId().toString()).isEqualTo("b2a1c3d4-5e6f-4789-a0b1-c2d3e4f50617");
        assertThat(event.context().correlationId().toString()).isEqualTo("0c9d8e7f-6a5b-4c3d-8e1f-0a9b8c7d6e5f");
    }

    @Test
    void payloadMapsToTheConsumersOwnTypeAndIgnoresUnknownFields() throws Exception {
        IncomingEvent event = IncomingEvent.parse(MAPPER, VALID);

        assertThat(event.payloadAs(MAPPER, Failed.class).reason()).isEqualTo("DECLINED");
    }

    @Test
    void aMissingRequiredFieldNamesTheField() {
        String noEventId = VALID.replace("\"eventId\":\"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee\",", "");

        assertThatThrownBy(() -> IncomingEvent.parse(MAPPER, noEventId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventId");
    }

    @Test
    void aMalformedIdIsAnIllegalArgument() {
        String badId = VALID.replace("b2a1c3d4-5e6f-4789-a0b1-c2d3e4f50617", "not-a-uuid");

        assertThatThrownBy(() -> IncomingEvent.parse(MAPPER, badId)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidJsonFailsWithAJsonException() {
        assertThatThrownBy(() -> IncomingEvent.parse(MAPPER, "this is not json"))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }
}
