package com.example.aiapp.billing;

import com.example.aiapp.billing.payment.PaymentGateway;
import com.example.aiapp.billing.payment.AlipayProperties;
import com.example.aiapp.billing.payment.WechatPayProperties;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.util.StringUtils;

import java.util.Locale;

@Configuration
@EnableConfigurationProperties({
        BillingProperties.class,
        AlipayProperties.class,
        WechatPayProperties.class
})
public class BillingConfig {

    @Bean
    ApplicationRunner billingProductionValidator(
            Environment environment,
            BillingProperties properties,
            AlipayProperties alipayProperties,
            WechatPayProperties wechatPayProperties,
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
            if ("ALIPAY".equals(provider)) {
                if (!alipayProperties.sdkConfigured() || !alipayProperties.sellerConfigured()) {
                    throw new IllegalStateException("Alipay SDK and seller settings must be configured.");
                }
                if (!"https://openapi.alipay.com/gateway.do".equals(alipayProperties.gatewayUrl())) {
                    throw new IllegalStateException("Production Alipay checkout must use the production gateway.");
                }
                if (!https(alipayProperties.notifyUrl()) || !https(alipayProperties.returnUrlBase())) {
                    throw new IllegalStateException("Production Alipay notify and return URLs must use HTTPS.");
                }
            } else if ("WECHAT_PAY".equals(provider)) {
                if (!wechatPayProperties.checkoutConfigured()) {
                    throw new IllegalStateException("WeChat Pay API v3 credentials and notify URL must be configured.");
                }
                if (wechatPayProperties.publicKeyPartiallyConfigured()) {
                    throw new IllegalStateException("WeChat Pay public key path and public key ID must be configured together.");
                }
                if (!properties.merchantId().trim().equals(wechatPayProperties.merchantId().trim())) {
                    throw new IllegalStateException("PAYMENT_MERCHANT_ID must match WECHAT_PAY_MERCHANT_ID.");
                }
                if (!https(wechatPayProperties.notifyUrl())) {
                    throw new IllegalStateException("Production WeChat Pay notify URL must use HTTPS.");
                }
            } else {
                if (!StringUtils.hasText(properties.webhookSecret())
                        || properties.webhookSecret().length() < 32) {
                    throw new IllegalStateException("PAYMENT_WEBHOOK_SECRET must be a strong secret.");
                }
                if (!https(properties.callbackUrl())) {
                    throw new IllegalStateException("PAYMENT_CALLBACK_URL must use HTTPS.");
                }
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

    private static boolean https(String value) {
        return StringUtils.hasText(value) && value.trim().startsWith("https://");
    }
}
