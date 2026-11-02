package dev.aadityaa.orderplatform.notification.email;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Simulator control for failure experiments. Unauthenticated on purpose: not a production endpoint. */
@RestController
@RequestMapping("/admin/email")
public class EmailAdminController {

    public record EmailUpdate(Double failureRate) {
    }

    public record EmailStatus(double failureRate, int emailsSent) {
    }

    private final EmailSettings settings;
    private final SimulatedEmailProvider provider;

    public EmailAdminController(EmailSettings settings, SimulatedEmailProvider provider) {
        this.settings = settings;
        this.provider = provider;
    }

    @GetMapping
    public EmailStatus get() {
        return new EmailStatus(settings.getFailureRate(), provider.sentCount());
    }

    @PutMapping
    public EmailStatus update(@RequestBody EmailUpdate update) {
        if (update.failureRate() != null) {
            if (update.failureRate() < 0 || update.failureRate() > 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "failureRate must be between 0 and 1");
            }
            settings.setFailureRate(update.failureRate());
        }
        return get();
    }
}
