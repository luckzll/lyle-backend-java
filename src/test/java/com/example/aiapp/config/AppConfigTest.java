package com.example.aiapp.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppConfigTest {

    @Test
    void aiServiceWebClientRequiresInternalToken() {
        AppConfig config = new AppConfig();

        assertThatThrownBy(() -> config.aiServiceWebClient(new AiServiceProperties(
                "http://localhost:8000",
                "",
                1024,
                1000,
                30
        ))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AI_SERVICE_INTERNAL_TOKEN");
    }

    @Test
    void aiServiceWebClientStartsWhenInternalTokenConfigured() {
        AppConfig config = new AppConfig();

        assertThatCode(() -> config.aiServiceWebClient(new AiServiceProperties(
                "http://localhost:8000",
                "test-internal-token",
                1024,
                1000,
                30
        ))).doesNotThrowAnyException();
    }

    @Test
    void productionSecurityRejectsDevelopmentDefaults() {
        AppConfig config = new AppConfig();

        assertThatThrownBy(() -> config.validateProductionSecurity(
                auth(
                        "lyle-dev-secret-key-please-change-to-at-least-32-bytes",
                        false,
                        false,
                        "http://localhost:5173"
                ),
                new UsageProperties(true, false, true, "change-this-usage-token", 10),
                new AiServiceProperties(
                        "http://ai-service:8000",
                        "change-this-internal-token",
                        1024,
                        1000,
                        30
                )
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void productionSecurityAcceptsStrongConfiguration() {
        AppConfig config = new AppConfig();

        assertThatCode(() -> config.validateProductionSecurity(
                auth(
                        "strong-production-jwt-secret-0123456789abcdef",
                        true,
                        false,
                        "https://app.example.com"
                ),
                new UsageProperties(
                        true,
                        false,
                        true,
                        "strong-production-usage-token-0123456789",
                        10
                ),
                new AiServiceProperties(
                        "http://ai-service:8000",
                        "strong-production-internal-token-0123456",
                        1024,
                        1000,
                        30
                )
        )).doesNotThrowAnyException();
    }

    private static AuthProperties auth(
            String jwtSecret,
            boolean secureCookie,
            boolean acceptLegacy,
            String allowedOrigins
    ) {
        return new AuthProperties(
                jwtSecret,
                "v64-current",
                "",
                "",
                acceptLegacy,
                15,
                30,
                "lyle-ai",
                "lyle-web",
                "lyle_refresh",
                secureCookie,
                true,
                allowedOrigins
        );
    }
}
