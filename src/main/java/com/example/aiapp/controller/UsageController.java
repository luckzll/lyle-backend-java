package com.example.aiapp.controller;

import com.example.aiapp.config.UsageProperties;
import com.example.aiapp.usage.UsageBatchRequest;
import com.example.aiapp.usage.UsageBatchResponse;
import com.example.aiapp.usage.UsageService;
import com.example.aiapp.utils.CurrentUserUtils;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
public class UsageController {

    private final UsageService usageService;
    private final UsageProperties properties;

    public UsageController(UsageService usageService, UsageProperties properties) {
        this.usageService = usageService;
        this.properties = properties;
    }

    @PostMapping("/internal/ai-usage/events:batch")
    UsageBatchResponse acceptBatch(
            @RequestHeader(value = "X-Usage-Internal-Token", required = false) String internalToken,
            @Valid @RequestBody UsageBatchRequest request
    ) {
        if (!constantTimeEquals(properties.internalToken(), internalToken)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return usageService.acceptBatch(request);
    }

    @GetMapping("/usage/summary")
    Map<String, Object> summary(@RequestParam(defaultValue = "today") String period) {
        requireUserView();
        return usageService.summary(CurrentUserUtils.currentUserId(), period);
    }

    @GetMapping("/usage/by-task")
    List<Map<String, Object>> byTask(@RequestParam(defaultValue = "today") String period) {
        requireUserView();
        return usageService.byTask(CurrentUserUtils.currentUserId(), period);
    }

    @GetMapping("/usage/trend")
    List<Map<String, Object>> trend(@RequestParam(defaultValue = "today") String period) {
        requireUserView();
        return usageService.trend(CurrentUserUtils.currentUserId(), period);
    }

    @GetMapping("/usage/turns")
    List<Map<String, Object>> turns(
            @RequestParam(defaultValue = "today") String period,
            @RequestParam(defaultValue = "20") int limit
    ) {
        requireUserView();
        return usageService.recentTurns(CurrentUserUtils.currentUserId(), period, limit);
    }

    @GetMapping("/usage/turns/{turnId}")
    Map<String, Object> turn(@PathVariable String turnId) {
        requireUserView();
        return usageService.recentTurns(CurrentUserUtils.currentUserId(), "month", 50).stream()
                .filter(item -> turnId.equals(item.get("turnId")))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @GetMapping("/usage/policy")
    Map<String, Object> policy() {
        requireUserView();
        return usageService.policy(CurrentUserUtils.currentUserId());
    }

    @PutMapping("/usage/policy")
    Map<String, Object> updatePolicy(@RequestBody Map<String, Long> request) {
        requireUserView();
        return usageService.updatePolicy(CurrentUserUtils.currentUserId(), request);
    }

    private void requireUserView() {
        if (!properties.userViewEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                actual.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
    }
}
