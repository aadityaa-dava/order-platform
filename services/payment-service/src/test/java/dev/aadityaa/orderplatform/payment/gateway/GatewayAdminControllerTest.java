package dev.aadityaa.orderplatform.payment.gateway;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GatewayAdminControllerTest {

    private GatewaySettings settings;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        settings = new GatewaySettings();
        mvc = MockMvcBuilders.standaloneSetup(new GatewayAdminController(settings)).build();
    }

    private org.springframework.test.web.servlet.ResultActions update(String json) throws Exception {
        return mvc.perform(put("/admin/gateway").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void getReturnsTheCurrentSettings() throws Exception {
        mvc.perform(get("/admin/gateway"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxAttempts").value(3))
                .andExpect(jsonPath("$.declineRate").value(0.0));
    }

    @Test
    void aPartialUpdateChangesOnlyTheGivenFields() throws Exception {
        update("{\"declineRate\":0.3,\"maxAttempts\":5}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.declineRate").value(0.3))
                .andExpect(jsonPath("$.maxAttempts").value(5))
                .andExpect(jsonPath("$.errorRate").value(0.0)); // untouched
    }

    @Test
    void ratesThatSumToMoreThanOneAreRejected() throws Exception {
        update("{\"declineRate\":0.6,\"errorRate\":0.6}").andExpect(status().isBadRequest());

        org.assertj.core.api.Assertions.assertThat(settings.getDeclineRate()).isZero(); // nothing was applied
    }

    @Test
    void invertedOrNegativeLatencyIsRejected() throws Exception {
        update("{\"minLatencyMs\":500,\"maxLatencyMs\":100}").andExpect(status().isBadRequest());
        update("{\"declineRate\":-0.1}").andExpect(status().isBadRequest());
    }
}
