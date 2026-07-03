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
}
