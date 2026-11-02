package dev.aadityaa.orderplatform.platform.messaging;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Keeps traces about business work: no trace for the scheduler's polling loop or for actuator scrapes. */
@AutoConfiguration
@ConditionalOnClass(ObservationPredicate.class)
public class ObservabilityAutoConfiguration {

    /** The outbox poller runs five times a second; an empty poll is not worth a trace. */
    @Bean
    ObservationPredicate scheduledTaskNoiseFilter() {
        return (name, context) -> !"tasks.scheduled.execution".equals(name);
    }

    @Configuration
    @ConditionalOnClass(name = "org.springframework.http.server.observation.ServerRequestObservationContext")
    static class WebNoiseFilter {

        /** Prometheus scrapes /actuator/prometheus every 5 seconds; those requests are not business traffic. */
        @Bean
        ObservationPredicate actuatorNoiseFilter() {
            return (name, context) -> !(context instanceof org.springframework.http.server.observation.ServerRequestObservationContext web
                    && web.getCarrier().getRequestURI().startsWith("/actuator"));
        }
    }
}
