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

        assertRoute("qwen3.7-flash", "qwen", "qwen3.7-flash", base);
        assertRoute("qwen3.7-plus", "qwen", "qwen3.7-plus", base);
        assertRoute("qwen3.8-max", "qwen", "qwen3.8-max", base);
        assertRoute("deepseek-v4-flash", "qwen", "deepseek-v4-flash", base);
        assertRoute("deepseek-v4-pro", "qwen", "deepseek-v4-pro", base);
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
