package com.example.aiapp.config;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.wechat-login")
public record WechatLoginProperties(boolean enabled, String appId, String appSecret, String callbackUrl) {
    public boolean configured() {
        if (!enabled || appId == null || !appId.matches("wx[a-zA-Z0-9]{16}")
                || appSecret == null || !appSecret.matches("[a-zA-Z0-9]{32}")) return false;
        try {
            URI uri = URI.create(callbackUrl);
            return "https".equals(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null
                    && "/api/auth/wechat/callback".equals(uri.getPath());
        } catch (RuntimeException e) { return false; }
    }
    // Do not include the record's secret in logs or diagnostics.
    @Override public String toString() { return "WechatLoginProperties[configured=" + configured() + "]"; }
}
