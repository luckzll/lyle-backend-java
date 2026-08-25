package com.example.aiapp.service.impl;

import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

final class ChatModelCatalog {

    private static final Map<String, ModelRoute> ROUTES = Map.of(
            "qwen-fast", new ModelRoute("qwen", "qwen3.5-flash"),
            "gpt-5.4", new ModelRoute("openai", "openai/gpt-5.4"),
            "gpt-5.5", new ModelRoute("openai", "openai/gpt-5.5"),
            "claude-sonnet-4.6", new ModelRoute("openai", "anthropic/claude-sonnet-4.6"),
            "gemini-3.1-pro", new ModelRoute("openai", "google/gemini-3.1-pro-preview")
    );

    private ChatModelCatalog() {
    }

    static Map<String, String> apply(String modelId, Map<String, String> baseConfig) {
        Map<String, String> result = new LinkedHashMap<>(baseConfig == null ? Map.of() : baseConfig);
        if (modelId == null || modelId.isBlank()) {
            return Map.copyOf(result);
        }
        ModelRoute route = ROUTES.get(modelId.trim());
        if (route == null) {
            throw new ResponseStatusException(BAD_REQUEST, "不支持的对话模型");
        }
        result.put("CHAT_MODEL_PROVIDER", route.provider());
        result.put("CHAT_MODEL_NAME", route.model());
        return Map.copyOf(result);
    }

    private record ModelRoute(String provider, String model) {
    }
}
