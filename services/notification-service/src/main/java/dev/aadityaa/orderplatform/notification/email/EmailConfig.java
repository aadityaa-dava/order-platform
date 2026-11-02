package dev.aadityaa.orderplatform.notification.email;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(EmailSettings.class)
public class EmailConfig {
}
