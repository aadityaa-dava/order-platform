package dev.aadityaa.orderplatform.payment.gateway;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(GatewaySettings.class)
public class GatewayConfig {
}
