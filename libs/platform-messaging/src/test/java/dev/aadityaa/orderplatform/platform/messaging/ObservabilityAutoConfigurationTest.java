package dev.aadityaa.orderplatform.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The filters that keep traces about business work only. */
class ObservabilityAutoConfigurationTest {

    private final ObservabilityAutoConfiguration config = new ObservabilityAutoConfiguration();

    @Test
    void theSchedulersPollingLoopIsNotTraced() {
        var predicate = config.scheduledTaskNoiseFilter();

        assertThat(predicate.test("tasks.scheduled.execution", new Observation.Context())).isFalse();
        assertThat(predicate.test("http.server.requests", new Observation.Context())).isTrue();
    }

    @Test
    void actuatorScrapesAreNotTracedButBusinessRequestsAre() {
        var predicate = new ObservabilityAutoConfiguration.WebNoiseFilter().actuatorNoiseFilter();

        assertThat(predicate.test("http.server.requests", requestTo("/actuator/prometheus"))).isFalse();
        assertThat(predicate.test("http.server.requests", requestTo("/actuator/health"))).isFalse();
        assertThat(predicate.test("http.server.requests", requestTo("/orders"))).isTrue();
        assertThat(predicate.test("kafka.publish", new Observation.Context())).isTrue(); // not a web observation
    }

    private static ServerRequestObservationContext requestTo(String uri) {
        return new ServerRequestObservationContext(new MockHttpServletRequest("GET", uri), new MockHttpServletResponse());
    }
}
