package com.example.aiapp.controller;

import com.example.aiapp.infrastructure.client.AiServiceClient;
import com.example.aiapp.service.AuditLogService;
import com.example.aiapp.utils.CurrentUserUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;

import static org.springframework.http.HttpStatus.ACCEPTED;
import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;

@RestController
@RequestMapping("/memory")
public class MemoryController {

    private final AiServiceClient aiServiceClient;
    private AuditLogService auditLogService;

    public MemoryController(AiServiceClient aiServiceClient) {
        this.aiServiceClient = aiServiceClient;
    }

    @Autowired(required = false)
    void setAuditLogService(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @GetMapping("/current")
    Map<String, Object> current(@RequestParam(required = false) String conversationId) {
        return aiServiceClient.getMemory(String.valueOf(CurrentUserUtils.currentUserId()), conversationId);
    }

    @GetMapping("/records")
    Map<String, Object> records(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "100") int limit
    ) {
        return aiServiceClient.getMemoryRecords(currentUserId(), status, query, Math.max(1, Math.min(limit, 300)));
    }

    @GetMapping("/records/{memoryId}")
    Map<String, Object> record(@PathVariable String memoryId) {
        return aiServiceClient.getMemoryRecord(currentUserId(), memoryId);
    }

    @GetMapping("/records/{memoryId}/sources")
    Map<String, Object> recordSources(@PathVariable String memoryId) {
        return aiServiceClient.getMemoryRecordSources(currentUserId(), memoryId);
    }

    @GetMapping("/inbox")
    Map<String, Object> inbox() {
        return aiServiceClient.getMemoryInbox(currentUserId());
    }

    @PostMapping("/conflicts/{conflictId}:resolve")
    Map<String, Object> resolveConflict(
            @PathVariable long conflictId,
            @RequestBody Map<String, Object> request
    ) {
        Map<String, Object> result = aiServiceClient.resolveMemoryConflict(
                currentUserId(), conflictId, validatedGovernanceRequest(request)
        );
        audit("resolve_conflict", "conflict:" + conflictId);
        return result;
    }

    @PostMapping("/records/{memoryId}:correct")
    Map<String, Object> correctRecord(@PathVariable String memoryId, @RequestBody Map<String, Object> request) {
        return govern(memoryId, "correct", request);
    }

    @PostMapping("/records/{memoryId}:disable")
    Map<String, Object> disableRecord(@PathVariable String memoryId, @RequestBody Map<String, Object> request) {
        return govern(memoryId, "disable", request);
    }

    @PostMapping("/records/{memoryId}:restore")
    Map<String, Object> restoreRecord(@PathVariable String memoryId, @RequestBody Map<String, Object> request) {
        return govern(memoryId, "restore", request);
    }

    @PostMapping("/records/{memoryId}:forget")
    Map<String, Object> forgetRecord(@PathVariable String memoryId, @RequestBody Map<String, Object> request) {
        return govern(memoryId, "forget", request);
    }

    @PostMapping("/records/{memoryId}:purge")
    ResponseEntity<Map<String, Object>> purgeRecord(
            @PathVariable String memoryId,
            @RequestBody Map<String, Object> request
    ) {
        Map<String, Object> result = aiServiceClient.governMemoryRecord(
                currentUserId(), memoryId, "purge", validatedGovernanceRequest(request)
        );
        audit("purge_requested", memoryId);
        return ResponseEntity.status(ACCEPTED).body(result);
    }

    @GetMapping("/purge-jobs/{jobId}")
    Map<String, Object> purgeJob(@PathVariable long jobId) {
        return aiServiceClient.getMemoryPurgeJob(currentUserId(), jobId);
    }

    @PostMapping("/purge-jobs/{jobId}:retry")
    Map<String, Object> retryPurgeJob(@PathVariable long jobId) {
        Map<String, Object> result = aiServiceClient.retryMemoryPurgeJob(currentUserId(), jobId);
        audit("purge_retry", "purge-job:" + jobId);
        return result;
    }

    @GetMapping("/governance-events")
    Map<String, Object> governanceEvents() {
        return aiServiceClient.getMemoryGovernanceEvents(currentUserId());
    }

    @PutMapping("/structured")
    Map<String, Object> saveStructured(@RequestBody Map<String, Object> request) {
        return aiServiceClient.upsertStructuredMemory(String.valueOf(CurrentUserUtils.currentUserId()), request);
    }

    @DeleteMapping("/structured/{key}")
    void deleteStructured(@PathVariable String key) {
        aiServiceClient.deleteStructuredMemory(String.valueOf(CurrentUserUtils.currentUserId()), key);
    }

    @DeleteMapping("/structured/{key}/purge")
    void purgeStructured(@PathVariable String key) {
        aiServiceClient.purgeStructuredMemory(String.valueOf(CurrentUserUtils.currentUserId()), key);
    }

    @PutMapping("/structured/{key}/status")
    Map<String, Object> changeStructuredStatus(@PathVariable String key, @RequestBody Map<String, String> request) {
        return aiServiceClient.changeStructuredMemoryStatus(
                String.valueOf(CurrentUserUtils.currentUserId()),
                key,
                request
        );
    }

    @GetMapping("/structured/{key}/history")
    Map<String, Object> structuredHistory(
            @PathVariable String key,
            @RequestParam(defaultValue = "30") int limit
    ) {
        return aiServiceClient.getStructuredMemoryHistory(
                String.valueOf(CurrentUserUtils.currentUserId()),
                key,
                limit
        );
    }

    @GetMapping("/structured/merge-suggestions")
    Map<String, Object> mergeSuggestions() {
        return aiServiceClient.getStructuredMemoryMergeSuggestions(String.valueOf(CurrentUserUtils.currentUserId()));
    }

    @GetMapping("/profiles")
    Map<String, Object> profiles(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "200") int limit
    ) {
        return aiServiceClient.getMemoryProfiles(
                String.valueOf(CurrentUserUtils.currentUserId()),
                status,
                limit
        );
    }

    @GetMapping("/fragments")
    Map<String, Object> fragments(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "200") int limit
    ) {
        return aiServiceClient.getMemoryFragments(
                String.valueOf(CurrentUserUtils.currentUserId()),
                status,
                category,
                limit
        );
    }

    @GetMapping("/profiles/{profileId}/evidence")
    Map<String, Object> profileEvidence(
            @PathVariable long profileId,
            @RequestParam(defaultValue = "100") int limit
    ) {
        return aiServiceClient.getProfileEvidence(
                String.valueOf(CurrentUserUtils.currentUserId()),
                profileId,
                limit
        );
    }

    @PutMapping("/profiles/schema-path/{schemaPath}/status")
    Map<String, Object> changeProfileStatus(@PathVariable String schemaPath, @RequestBody Map<String, String> request) {
        return aiServiceClient.changeProfileStatus(
                String.valueOf(CurrentUserUtils.currentUserId()),
                schemaPath,
                request
        );
    }

    @PutMapping("/fragments/{fragmentId}/status")
    Map<String, Object> changeFragmentStatus(@PathVariable long fragmentId, @RequestBody Map<String, String> request) {
        return aiServiceClient.changeFragmentStatus(
                String.valueOf(CurrentUserUtils.currentUserId()),
                fragmentId,
                request
        );
    }

    private Map<String, Object> govern(String memoryId, String action, Map<String, Object> request) {
        Map<String, Object> result = aiServiceClient.governMemoryRecord(
                currentUserId(), memoryId, action, validatedGovernanceRequest(request)
        );
        audit(action, memoryId);
        return result;
    }

    private Map<String, Object> validatedGovernanceRequest(Map<String, Object> request) {
        String requestId = String.valueOf(request == null ? "" : request.getOrDefault("clientRequestId", ""));
        try {
            UUID.fromString(requestId);
        } catch (IllegalArgumentException exception) {
            throw new org.springframework.web.server.ResponseStatusException(
                    UNPROCESSABLE_ENTITY,
                    "clientRequestId 必须是 UUID"
            );
        }
        return request == null ? Map.of() : new LinkedHashMap<>(request);
    }

    private String currentUserId() {
        return String.valueOf(CurrentUserUtils.currentUserId());
    }

    private void audit(String action, String targetId) {
        if (auditLogService != null) {
            auditLogService.record(CurrentUserUtils.currentUserId(), action, "memory", targetId);
        }
    }
}
