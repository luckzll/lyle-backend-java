package com.example.aiapp.billing.payment;

import com.example.aiapp.billing.BillingProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 支付渠道注册表。
 *
 * <p>配置项支持按顺序填写多个渠道，例如 {@code ALIPAY,WECHAT_PAY}。
 * 第一项是默认渠道；每笔支付后续操作始终按支付记录中的渠道执行。</p>
 */
@Component
public class PaymentGatewayRegistry {

    private final List<String> enabledProviders;
    private final Map<String, PaymentGateway> gateways;

    public PaymentGatewayRegistry(BillingProperties properties, List<PaymentGateway> gateways) {
        this.enabledProviders = parseProviders(properties.paymentProvider());
        Map<String, PaymentGateway> registered = new LinkedHashMap<>();
        for (PaymentGateway gateway : gateways) {
            registered.put(normalize(gateway.provider()), gateway);
        }
        this.gateways = Map.copyOf(registered);
    }

    public List<String> enabledProviders() {
        return enabledProviders;
    }

    public String defaultProvider() {
        if (enabledProviders.isEmpty()) {
            throw unavailable();
        }
        return enabledProviders.get(0);
    }

    public boolean isEnabled(String provider) {
        return enabledProviders.contains(normalize(provider));
    }

    public PaymentGateway requireEnabled(String provider) {
        String normalized = StringUtils.hasText(provider) ? normalize(provider) : defaultProvider();
        if (!enabledProviders.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持所选支付方式。");
        }
        return requireInstalled(normalized);
    }

    public PaymentGateway requireForStoredProvider(String provider) {
        String normalized = normalize(provider);
        if (!enabledProviders.contains(normalized)) {
            throw unavailable();
        }
        return requireInstalled(normalized);
    }

    public PaymentGateway requireInstalled(String provider) {
        PaymentGateway gateway = gateways.get(normalize(provider));
        if (gateway == null) {
            throw unavailable();
        }
        return gateway;
    }

    public static List<String> parseProviders(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of("MOCK");
        }
        return Arrays.stream(value.trim().split("[,;\\s]+"))
                .map(PaymentGatewayRegistry::normalize)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    public static String normalize(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return "WECHAT".equals(normalized) ? "WECHAT_PAY" : normalized;
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "当前支付渠道尚未配置完成。");
    }
}
