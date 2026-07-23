package com.example.aiapp.controller;

import com.example.aiapp.infrastructure.client.AiServiceClient;
import com.example.aiapp.utils.CurrentUserUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

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

    @PostMapping("/events")
    Map<String, Object> productEvent(@RequestBody Map<String, Object> request) {
        return ai.postJson("/ai/experience/events/{userId}", request, userId());
    }

    @PostMapping("/public-events")
    Map<String, Object> publicProductEvent(@RequestBody Map<String, Object> request) {
        Object eventType = request.get("eventType");
        Object page = request.get("page");
        if (!(eventType instanceof String type)
                || !(type.equals("auth_view_total")
                || type.equals("auth_submit_total")
                || type.equals("auth_return_to_target_total"))
                || !"login".equals(page)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only anonymous authentication metrics are accepted");
        }
        return ai.postJson("/ai/experience/events/{userId}", request, "anonymous-auth");
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
                                @RequestParam(required = false) Long automationId,
                                @RequestParam(defaultValue = "false") boolean attentionOnly,
                                @RequestParam(required = false) String cursor,
                                @RequestParam(defaultValue = "50") int limit) {
        return ai.getExperienceResults(userId(), status, projectId, automationId, attentionOnly, cursor, limit);
    }

    @GetMapping("/execution/results/{resultId}")
    Map<String, Object> result(@PathVariable long resultId) {
        return ai.getJson("/ai/experience/execution/{userId}/results/{resultId}", userId(), resultId);
    }

    @PostMapping("/execution/results/{resultId}/dismiss")
    Map<String, Object> dismissResult(@PathVariable long resultId) {
        return ai.postJson(
                "/ai/experience/execution/{userId}/results/{resultId}/dismiss",
                Map.of(), userId(), resultId
        );
    }

    @PostMapping("/execution/results/{resultId}/restore")
    Map<String, Object> restoreResult(@PathVariable long resultId) {
        return ai.postJson(
                "/ai/experience/execution/{userId}/results/{resultId}/restore",
                Map.of(), userId(), resultId
        );
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
