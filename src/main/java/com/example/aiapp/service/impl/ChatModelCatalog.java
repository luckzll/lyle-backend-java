package com.example.aiapp.service.impl;

import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

final class ChatModelCatalog {

    private static final Map<String, ModelRoute> ROUTES = Map.of(
            "qwen3.7-flash", new ModelRoute("qwen", "qwen3.7-flash"),
            "qwen3.7-plus", new ModelRoute("qwen", "qwen3.7-plus"),
            "qwen3.8-max", new ModelRoute("qwen", "qwen3.8-max"),
            "deepseek-v4-flash", new ModelRoute("qwen", "deepseek-v4-flash"),
            "deepseek-v4-pro", new ModelRoute("qwen", "deepseek-v4-pro"),
            "kimi-k3", new ModelRoute("qwen", "kimi-k3")
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
