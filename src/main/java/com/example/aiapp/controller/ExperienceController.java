package com.example.aiapp.controller;

import com.example.aiapp.infrastructure.client.AiServiceClient;
import com.example.aiapp.utils.CurrentUserUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/experience")
public class ExperienceController {
    private final AiServiceClient ai;

    public ExperienceController(AiServiceClient ai) {
        this.ai = ai;
    }

    @GetMapping("/templates")
    Map<String, Object> templates() {
        return ai.getJson("/ai/experience/templates/{userId}", userId());
    }

    @GetMapping("/onboarding")
    Map<String, Object> onboarding() {
        return ai.getJson("/ai/experience/onboarding/{userId}", userId());
    }

    @PatchMapping("/onboarding")
    Map<String, Object> updateOnboarding(@RequestBody Map<String, Object> request) {
        return ai.patchJson("/ai/experience/onboarding/{userId}", request, userId());
    }

    @PostMapping("/onboarding/first-run")
    Map<String, Object> firstRun(@RequestBody Map<String, Object> request) {
        return ai.postJson("/ai/experience/onboarding/{userId}/first-run", request, userId());
    }

    @PostMapping("/drafts/from-template")
    Map<String, Object> fromTemplate(@RequestBody Map<String, Object> request) {
        return ai.postJson("/ai/experience/drafts/{userId}/from-template", request, userId());
    }

    @PostMapping("/drafts/from-intent")
    Map<String, Object> fromIntent(@RequestBody Map<String, Object> request) {
        return ai.postJson("/ai/experience/drafts/{userId}/from-intent", request, userId());
    }

    @GetMapping("/drafts/{draftId}")
    Map<String, Object> draft(@PathVariable long draftId) {
        return ai.getJson("/ai/experience/drafts/{userId}/{draftId}", userId(), draftId);
    }

    @PostMapping("/drafts/{draftId}/enable")
    Map<String, Object> enableDraft(@PathVariable long draftId, @RequestBody(required = false) Map<String, Object> request) {
        return ai.postJson("/ai/experience/drafts/{userId}/{draftId}/enable", request, userId(), draftId);
    }

    @PostMapping("/drafts/{draftId}/discard")
    Map<String, Object> discardDraft(@PathVariable long draftId) {
        return ai.postJson("/ai/experience/drafts/{userId}/{draftId}/discard", Map.of(), userId(), draftId);
    }

    @GetMapping("/execution/results")
    Map<String, Object> results(@RequestParam(required = false) String status,
                                @RequestParam(required = false) Long projectId,
                                @RequestParam(defaultValue = "50") int limit) {
        String path = "/ai/experience/execution/{userId}/results?limit={limit}"
                + (status == null ? "" : "&status={status}")
                + (projectId == null ? "" : "&projectId={projectId}");
        if (status != null && projectId != null) return ai.getJson(path, userId(), limit, status, projectId);
        if (status != null) return ai.getJson(path, userId(), limit, status);
        if (projectId != null) return ai.getJson(path, userId(), limit, projectId);
        return ai.getJson(path, userId(), limit);
    }

    @GetMapping("/execution/results/{resultId}")
    Map<String, Object> result(@PathVariable long resultId) {
        return ai.getJson("/ai/experience/execution/{userId}/results/{resultId}", userId(), resultId);
    }

    @GetMapping("/execution/needs-attention")
    Map<String, Object> needsAttention() {
        return ai.getJson("/ai/experience/execution/{userId}/needs-attention", userId());
    }

    @GetMapping("/confirmations")
    Map<String, Object> confirmations() {
        return ai.getJson("/ai/experience/confirmations/{userId}", userId());
    }

    @GetMapping("/confirmations/{operationId}")
    Map<String, Object> confirmation(@PathVariable long operationId) {
        return ai.getJson("/ai/experience/confirmations/{userId}/{operationId}", userId(), operationId);
    }

    @PostMapping("/confirmations/{operationId}/confirm")
    Map<String, Object> confirm(@PathVariable long operationId) {
        return ai.postJson("/ai/experience/confirmations/{userId}/{operationId}/confirm", Map.of(), userId(), operationId);
    }

    @PostMapping("/confirmations/{operationId}/reject")
    Map<String, Object> reject(@PathVariable long operationId) {
        return ai.postJson("/ai/experience/confirmations/{userId}/{operationId}/reject", Map.of(), userId(), operationId);
    }

    @GetMapping("/connections/recommended")
    Map<String, Object> recommendedConnections() {
        return ai.getJson("/ai/experience/connections/{userId}/recommended", userId());
    }

    @GetMapping("/connections/diagnostics")
    Map<String, Object> connectionDiagnostics() {
        return ai.getJson("/ai/experience/connections/{userId}/diagnostics", userId());
    }

    @PostMapping("/connections/{connectionId}/diagnose")
    Map<String, Object> diagnose(@PathVariable String connectionId) {
        return ai.postJson("/ai/experience/connections/{userId}/{connectionId}/diagnose", Map.of(), userId(), connectionId);
    }

    private String userId() {
        return String.valueOf(CurrentUserUtils.currentUserId());
    }
}
