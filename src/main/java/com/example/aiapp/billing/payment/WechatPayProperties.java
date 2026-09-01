package com.example.aiapp.billing.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "app.billing.wechat-pay")
public record WechatPayProperties(
        String appId,
        String merchantId,
        String merchantSerialNumber,
        String merchantPrivateKeyPath,
        String apiV3Key,
        String wechatPayPublicKeyPath,
        String wechatPayPublicKeyId,
        String notifyUrl
) {

    public boolean credentialsConfigured() {
        return StringUtils.hasText(appId)
                && StringUtils.hasText(merchantId)
                && StringUtils.hasText(merchantSerialNumber)
                && StringUtils.hasText(merchantPrivateKeyPath)
                && StringUtils.hasText(apiV3Key)
                && apiV3Key.trim().length() == 32;
    }

    public boolean publicKeyConfigured() {
        return StringUtils.hasText(wechatPayPublicKeyPath)
                && StringUtils.hasText(wechatPayPublicKeyId);
    }

    public boolean publicKeyPartiallyConfigured() {
        return StringUtils.hasText(wechatPayPublicKeyPath)
                != StringUtils.hasText(wechatPayPublicKeyId);
    }

    public boolean checkoutConfigured() {
        return credentialsConfigured() && StringUtils.hasText(notifyUrl);
    }

    public boolean merchantMatches(String actualMerchantId, String actualAppId) {
        return credentialsConfigured()
                && merchantId.trim().equals(actualMerchantId)
                && appId.trim().equals(actualAppId);
    }
}
