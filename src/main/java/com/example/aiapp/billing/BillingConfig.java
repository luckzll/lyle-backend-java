package com.example.aiapp.billing;

import com.example.aiapp.billing.payment.PaymentGateway;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.util.StringUtils;

import java.util.Locale;

@Configuration
@EnableConfigurationProperties(BillingProperties.class)
public class BillingConfig {

    @Bean
    ApplicationRunner billingProductionValidator(
            Environment environment,
            BillingProperties properties,
            PaymentGateway gateway
    ) {
        return args -> {
            if (!environment.acceptsProfiles(Profiles.of("prod", "production"))) {
                return;
            }
            if (properties.quotaEnforcementEnabled() && !properties.ledgerEnabled()) {
                throw new IllegalStateException(
                        "Production quota enforcement requires the commercial ledger."
                );
            }
            if (properties.quotaEnforcementEnabled() && properties.settlementShadowEnabled()) {
                throw new IllegalStateException(
                        "Production quota enforcement requires active settlement."
                );
            }
            if (!properties.checkoutEnabled()) {
                return;
            }
            String provider = normalized(properties.paymentProvider());
            if ("MOCK".equals(provider)) {
                throw new IllegalStateException("Production PAYMENT_PROVIDER cannot be MOCK.");
            }
            if (!provider.equals(gateway.provider())) {
                throw new IllegalStateException(
                        "The configured production payment provider adapter is not installed."
                );
            }
            if (!StringUtils.hasText(properties.merchantId())) {
                throw new IllegalStateException("PAYMENT_MERCHANT_ID must be configured.");
            }
            if (!StringUtils.hasText(properties.webhookSecret())
                    || properties.webhookSecret().length() < 32) {
                throw new IllegalStateException("PAYMENT_WEBHOOK_SECRET must be a strong secret.");
            }
            if (!StringUtils.hasText(properties.callbackUrl())
                    || !properties.callbackUrl().startsWith("https://")) {
                throw new IllegalStateException("PAYMENT_CALLBACK_URL must use HTTPS.");
            }
            if (!properties.webhookEnabled() || !properties.fulfillmentWorkerEnabled()) {
                throw new IllegalStateException(
                        "Payment webhook and fulfillment worker must be enabled with production checkout."
                );
            }
        };
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
