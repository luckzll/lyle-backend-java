package com.example.aiapp.controller;

import com.example.aiapp.config.AiServiceProperties;
import com.example.aiapp.service.MemorySourceValidationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/**
 * 记忆来源校验接口。
 */
@RestController
public class MemorySourceValidationController {

    private final MemorySourceValidationService validationService;
    private final AiServiceProperties properties;

    public MemorySourceValidationController(
            MemorySourceValidationService validationService,
            AiServiceProperties properties
    ) {
        this.validationService = validationService;
        this.properties = properties;
    }

    @PostMapping("/internal/memory-sources:validate")
    @SuppressWarnings("unchecked")
    Map<String, Object> validate(
            @RequestHeader(value = "X-Internal-Token", required = false) String internalToken,
            @RequestBody Map<String, Object> request
    ) {
        if (!constantTimeEquals(properties.internalToken(), internalToken)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        String userId = String.valueOf(request.getOrDefault("userId", ""));
        Object rawSources = request.get("sources");
        List<Map<String, Object>> sources = rawSources instanceof List<?> values
                ? values.stream()
                .filter(Map.class::isInstance)
                .map(value -> (Map<String, Object>) value)
                .toList()
                : List.of();
        return Map.of("items", validationService.validate(userId, sources));
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null || expected.isBlank() || actual.isBlank()) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8)
        );
    }
}
