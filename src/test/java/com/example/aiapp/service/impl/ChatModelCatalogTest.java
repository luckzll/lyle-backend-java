package com.example.aiapp.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatModelCatalogTest {

    @Test
    void appliesEverySupportedPublicModelIdWithoutDroppingBaseConfig() {
        Map<String, String> base = Map.of("REVIEWER_ENABLED", "true");

        assertRoute("qwen-fast", "qwen", "qwen3.5-flash", base);
        assertRoute("gpt-5.4", "openai", "openai/gpt-5.4", base);
        assertRoute("gpt-5.5", "openai", "openai/gpt-5.5", base);
        assertRoute("claude-sonnet-4.6", "openai", "anthropic/claude-sonnet-4.6", base);
        assertRoute("gemini-3.1-pro", "openai", "google/gemini-3.1-pro-preview", base);
    }

    @Test
    void rejectsArbitraryClientSuppliedModels() {
        assertThatThrownBy(() -> ChatModelCatalog.apply("attacker/model", Map.of()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("不支持的对话模型");
    }

    @Test
    void keepsConfiguredDefaultForOlderClients() {
        Map<String, String> base = Map.of("REVIEWER_ENABLED", "true");
        assertThat(ChatModelCatalog.apply(null, base)).isEqualTo(base);
    }

    private void assertRoute(
            String modelId,
            String provider,
            String model,
            Map<String, String> base
    ) {
        Map<String, String> result = ChatModelCatalog.apply(modelId, base);
        assertThat(result)
                .containsEntry("REVIEWER_ENABLED", "true")
                .containsEntry("CHAT_MODEL_PROVIDER", provider)
                .containsEntry("CHAT_MODEL_NAME", model);
    }
}
