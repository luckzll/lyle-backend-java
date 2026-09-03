package com.example.aiapp.billing.payment;

import com.example.aiapp.billing.BillingProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaymentGatewayRegistryTest {

    @Test
    void enablesBothProvidersAndKeepsConfiguredOrder() {
        PaymentGateway alipay = gateway("ALIPAY");
        PaymentGateway wechat = gateway("WECHAT_PAY");
        PaymentGatewayRegistry registry = new PaymentGatewayRegistry(
                properties("ALIPAY,WECHAT_PAY"),
                List.of(wechat, alipay)
        );

        assertThat(registry.enabledProviders()).containsExactly("ALIPAY", "WECHAT_PAY");
        assertThat(registry.defaultProvider()).isEqualTo("ALIPAY");
        assertThat(registry.requireEnabled("WECHAT")).isSameAs(wechat);
    }

    @Test
    void rejectsAProviderThatIsInstalledButNotEnabled() {
        PaymentGatewayRegistry registry = new PaymentGatewayRegistry(
                properties("ALIPAY"),
                List.of(gateway("ALIPAY"), gateway("WECHAT_PAY"))
        );

        assertThatThrownBy(() -> registry.requireEnabled("WECHAT_PAY"))
                .hasMessageContaining("不支持所选支付方式");
    }

    private static PaymentGateway gateway(String provider) {
        PaymentGateway gateway = mock(PaymentGateway.class);
        when(gateway.provider()).thenReturn(provider);
        return gateway;
    }

    private static BillingProperties properties(String providers) {
        return new BillingProperties(
                true, true, false, false, true, true, true, false, true,
                "CN", "CNY", providers, "", "", "", 30, 10, 1_000_000, 100_000_000
        );
    }
}
