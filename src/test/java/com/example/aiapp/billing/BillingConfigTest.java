package com.example.aiapp.billing;

import com.example.aiapp.billing.payment.PaymentGateway;
import com.example.aiapp.billing.payment.AlipayProperties;
import com.example.aiapp.billing.payment.WechatPayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BillingConfigTest {

    @Test
    void productionCheckoutRejectsMockProvider() {
        Environment environment = productionEnvironment();
        PaymentGateway gateway = mock(PaymentGateway.class);
        when(gateway.provider()).thenReturn("MOCK");
        ApplicationRunner runner = new BillingConfig().billingProductionValidator(
                environment,
                properties("MOCK", true),
                alipayProperties(),
                wechatPayProperties(),
                gateway
        );

        assertThatThrownBy(() -> runner.run(mock(ApplicationArguments.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be MOCK");
    }

    @Test
    void productionCanStayInPlatformPendingModeWithCheckoutDisabled() {
        Environment environment = productionEnvironment();
        PaymentGateway gateway = mock(PaymentGateway.class);
        when(gateway.provider()).thenReturn("MOCK");
        ApplicationRunner runner = new BillingConfig().billingProductionValidator(
                environment,
                properties("MOCK", false),
                alipayProperties(),
                wechatPayProperties(),
                gateway
        );

        assertThatCode(() -> runner.run(mock(ApplicationArguments.class))).doesNotThrowAnyException();
    }

    @Test
    void productionQuotaEnforcementRequiresCommercialLedger() {
        Environment environment = productionEnvironment();
        PaymentGateway gateway = mock(PaymentGateway.class);
        ApplicationRunner runner = new BillingConfig().billingProductionValidator(
                environment,
                properties("MOCK", false, false, false, true),
                alipayProperties(),
                wechatPayProperties(),
                gateway
        );

        assertThatThrownBy(() -> runner.run(mock(ApplicationArguments.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires the commercial ledger");
    }

    @Test
    void productionQuotaEnforcementRejectsShadowSettlement() {
        Environment environment = productionEnvironment();
        PaymentGateway gateway = mock(PaymentGateway.class);
        ApplicationRunner runner = new BillingConfig().billingProductionValidator(
                environment,
                properties("MOCK", false, true, true, true),
                alipayProperties(),
                wechatPayProperties(),
                gateway
        );

        assertThatThrownBy(() -> runner.run(mock(ApplicationArguments.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires active settlement");
    }

    @Test
    void nonProductionCanUseMockForSandboxDevelopment() {
        Environment environment = mock(Environment.class);
        when(environment.acceptsProfiles(Profiles.of("prod", "production"))).thenReturn(false);
        PaymentGateway gateway = mock(PaymentGateway.class);
        when(gateway.provider()).thenReturn("MOCK");
        ApplicationRunner runner = new BillingConfig().billingProductionValidator(
                environment,
                properties("MOCK", true),
                alipayProperties(),
                wechatPayProperties(),
                gateway
        );

        assertThatCode(() -> runner.run(mock(ApplicationArguments.class))).doesNotThrowAnyException();
    }

    @Test
    void productionAlipayRequiresProductionGatewayAndHttpsCallbacks() {
        Environment environment = productionEnvironment();
        PaymentGateway gateway = mock(PaymentGateway.class);
        when(gateway.provider()).thenReturn("ALIPAY");
        AlipayProperties alipay = new AlipayProperties(
                "https://openapi-sandbox.dl.alipaydev.com/gateway.do",
                "app-id",
                "private-key",
                "alipay-public-key",
                "seller-id",
                "",
                "https://api.example.com/api/billing/webhooks/alipay/notify",
                "https://app.example.com/billing/result"
        );
        ApplicationRunner runner = new BillingConfig().billingProductionValidator(
                environment,
                properties("ALIPAY", true),
                alipay,
                wechatPayProperties(),
                gateway
        );

        assertThatThrownBy(() -> runner.run(mock(ApplicationArguments.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("production gateway");
    }

    @Test
    void productionAlipayAcceptsCompleteProductionConfiguration() {
        Environment environment = productionEnvironment();
        PaymentGateway gateway = mock(PaymentGateway.class);
        when(gateway.provider()).thenReturn("ALIPAY");
        AlipayProperties alipay = new AlipayProperties(
                "https://openapi.alipay.com/gateway.do",
                "app-id",
                "private-key",
                "alipay-public-key",
                "seller-id",
                "",
                "https://api.example.com/api/billing/webhooks/alipay/notify",
                "https://app.example.com/billing/result"
        );
        ApplicationRunner runner = new BillingConfig().billingProductionValidator(
                environment,
                properties("ALIPAY", true),
                alipay,
                wechatPayProperties(),
                gateway
        );

        assertThatCode(() -> runner.run(mock(ApplicationArguments.class))).doesNotThrowAnyException();
    }

    @Test
    void productionWechatPayAcceptsCompleteApiV3Configuration() {
        Environment environment = productionEnvironment();
        PaymentGateway gateway = mock(PaymentGateway.class);
        when(gateway.provider()).thenReturn("WECHAT_PAY");
        WechatPayProperties wechatPay = new WechatPayProperties(
                "wx-app-id",
                "merchant",
                "merchant-certificate-serial",
                "C:/run/secrets/apiclient_key.pem",
                "12345678901234567890123456789012",
                "",
                "",
                "https://api.example.com/api/billing/webhooks/wechat-pay/notify"
        );
        ApplicationRunner runner = new BillingConfig().billingProductionValidator(
                environment,
                properties("WECHAT_PAY", true),
                alipayProperties(),
                wechatPay,
                gateway
        );

        assertThatCode(() -> runner.run(mock(ApplicationArguments.class))).doesNotThrowAnyException();
    }

    @Test
    void productionWechatPayRejectsPartialPublicKeyConfiguration() {
        Environment environment = productionEnvironment();
        PaymentGateway gateway = mock(PaymentGateway.class);
        when(gateway.provider()).thenReturn("WECHAT_PAY");
        WechatPayProperties wechatPay = new WechatPayProperties(
                "wx-app-id",
                "merchant",
                "merchant-certificate-serial",
                "C:/run/secrets/apiclient_key.pem",
                "12345678901234567890123456789012",
                "C:/run/secrets/wechatpay_public_key.pem",
                "",
                "https://api.example.com/api/billing/webhooks/wechat-pay/notify"
        );
        ApplicationRunner runner = new BillingConfig().billingProductionValidator(
                environment,
                properties("WECHAT_PAY", true),
                alipayProperties(),
                wechatPay,
                gateway
        );

        assertThatThrownBy(() -> runner.run(mock(ApplicationArguments.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("public key path");
    }

    private static Environment productionEnvironment() {
        Environment environment = mock(Environment.class);
        when(environment.acceptsProfiles(Profiles.of("prod", "production"))).thenReturn(true);
        return environment;
    }

    private static BillingProperties properties(String provider, boolean checkout) {
        return properties(provider, checkout, true, true, false);
    }

    private static BillingProperties properties(
            String provider,
            boolean checkout,
            boolean ledger,
            boolean settlementShadow,
            boolean quotaEnforcement
    ) {
        return new BillingProperties(
                true,
                ledger,
                settlementShadow,
                quotaEnforcement,
                checkout,
                true,
                true,
                true,
                true,
                "CN",
                "CNY",
                provider,
                "merchant",
                "strong-webhook-secret-0123456789abcdef",
                "https://app.example.com/api/billing/webhooks/provider",
                30,
                10,
                1_000_000,
                100_000_000
        );
    }

    private static AlipayProperties alipayProperties() {
        return new AlipayProperties("", "", "", "", "", "", "", "");
    }

    private static WechatPayProperties wechatPayProperties() {
        return new WechatPayProperties("", "", "", "", "", "", "", "");
    }
}
