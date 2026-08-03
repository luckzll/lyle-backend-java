package com.example.aiapp.billing;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.billing")
public record BillingProperties(
        boolean catalogEnabled,
        boolean ledgerEnabled,
        boolean settlementShadowEnabled,
        boolean quotaEnforcementEnabled,
        boolean checkoutEnabled,
        boolean webhookEnabled,
        boolean fulfillmentWorkerEnabled,
        boolean reconciliationEnabled,
        boolean userViewEnabled,
        String market,
        String currency,
        String paymentProvider,
        String merchantId,
        String webhookSecret,
        String callbackUrl,
        long orderTtlMinutes,
        long reservationTtlMinutes,
        long reservationCreditMicros,
        long freeCreditMicros
) {
}
