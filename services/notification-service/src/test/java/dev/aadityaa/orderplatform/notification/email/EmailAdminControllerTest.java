package dev.aadityaa.orderplatform.notification.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class EmailAdminControllerTest {

    private EmailSettings settings;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        settings = new EmailSettings();
        settings.setMinLatencyMs(0);
        settings.setMaxLatencyMs(0);
        var provider = new SimulatedEmailProvider(settings, () -> 0.99);
        provider.send("k1", "a@example.com", "S", "B");
        mvc = MockMvcBuilders.standaloneSetup(new EmailAdminController(settings, provider)).build();
    }

    @Test
    void getReportsTheFailureRateAndHowManyEmailsWereSent() throws Exception {
        mvc.perform(get("/admin/email"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureRate").value(0.0))
                .andExpect(jsonPath("$.emailsSent").value(1));
    }

    @Test
    void aValidFailureRateIsApplied() throws Exception {
        mvc.perform(put("/admin/email").contentType(MediaType.APPLICATION_JSON).content("{\"failureRate\":0.4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureRate").value(0.4));

        assertThat(settings.getFailureRate()).isEqualTo(0.4);
    }

    @Test
    void aFailureRateOutsideZeroToOneIsRejected() throws Exception {
        mvc.perform(put("/admin/email").contentType(MediaType.APPLICATION_JSON).content("{\"failureRate\":1.5}"))
                .andExpect(status().isBadRequest());

        assertThat(settings.getFailureRate()).isZero();
    }
}
