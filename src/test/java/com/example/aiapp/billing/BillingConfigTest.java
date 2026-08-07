package com.example.aiapp.billing;

import com.example.aiapp.billing.payment.PaymentGateway;
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
                gateway
        );

        assertThatCode(() -> runner.run(mock(ApplicationArguments.class))).doesNotThrowAnyException();
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
}
