package com.example.aiapp.billing.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "app.billing.alipay")
public record AlipayProperties(
        String gatewayUrl,
        String appId,
        String appPrivateKey,
        String alipayPublicKey,
        String sellerId,
        String sellerEmail,
        String notifyUrl,
        String returnUrlBase
) {

    public boolean sdkConfigured() {
        return StringUtils.hasText(gatewayUrl)
                && StringUtils.hasText(appId)
                && StringUtils.hasText(appPrivateKey)
                && StringUtils.hasText(alipayPublicKey);
    }

    public boolean sellerConfigured() {
        return StringUtils.hasText(sellerId) || StringUtils.hasText(sellerEmail);
    }

    public boolean sellerMatches(String actualSellerId, String actualSellerEmail) {
        return (StringUtils.hasText(sellerId) && sellerId.trim().equals(actualSellerId))
                || (StringUtils.hasText(sellerEmail) && sellerEmail.trim().equals(actualSellerEmail));
    }
}
