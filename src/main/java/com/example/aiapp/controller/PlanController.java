package com.example.aiapp.controller;

import com.example.aiapp.infrastructure.client.AiServiceClient;
import com.example.aiapp.utils.CurrentUserUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/agent/plans")
public class PlanController {

    private final AiServiceClient aiServiceClient;

    public PlanController(AiServiceClient aiServiceClient) {
        this.aiServiceClient = aiServiceClient;
    }

    @GetMapping
    Map<String, Object> list(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit
    ) {
        return aiServiceClient.listPlans(currentUserId(), projectId, status, limit);
    }

    @PostMapping
    Map<String, Object> create(@RequestBody Map<String, Object> request) {
        return aiServiceClient.createPlan(currentUserId(), request);
    }

    @GetMapping("/{planId}")
    Map<String, Object> detail(@PathVariable long planId) {
        return aiServiceClient.getPlan(currentUserId(), planId);
    }

    @PostMapping("/{planId}/confirm")
    Map<String, Object> confirm(@PathVariable long planId) {
        return aiServiceClient.transitionPlan(currentUserId(), planId, "confirm");
    }

    @PostMapping("/{planId}/pause")
    Map<String, Object> pause(@PathVariable long planId) {
        return aiServiceClient.transitionPlan(currentUserId(), planId, "pause");
    }

    @PostMapping("/{planId}/resume")
    Map<String, Object> resume(@PathVariable long planId) {
        return aiServiceClient.transitionPlan(currentUserId(), planId, "resume");
    }

    @PostMapping("/{planId}/cancel")
    Map<String, Object> cancel(@PathVariable long planId) {
        return aiServiceClient.transitionPlan(currentUserId(), planId, "cancel");
    }

    private String currentUserId() {
        return String.valueOf(CurrentUserUtils.currentUserId());
    }
}
