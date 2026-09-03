package com.example.aiapp.controller;

import com.example.aiapp.infrastructure.client.AiServiceClient;
import com.example.aiapp.utils.CurrentUserUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 智能工作台接口。
 */
@RestController
@RequestMapping("/workbench")
public class WorkbenchController {

    private final AiServiceClient aiServiceClient;

    public WorkbenchController(AiServiceClient aiServiceClient) {
        this.aiServiceClient = aiServiceClient;
    }

    @GetMapping("/mcp/servers")
    Map<String, Object> mcpServers() {
        return aiServiceClient.getJson("/ai/workbench/mcp/{userId}/servers", currentUserId());
    }

    @PostMapping("/mcp/servers")
    Map<String, Object> createMcpServer(@RequestBody Map<String, Object> request) {
        return aiServiceClient.postJson("/ai/workbench/mcp/{userId}/servers", request, currentUserId());
    }

    @PatchMapping("/mcp/servers/{serverId}/status")
    Map<String, Object> updateMcpServerStatus(@PathVariable long serverId, @RequestBody Map<String, Object> request) {
        return aiServiceClient.patchJson("/ai/workbench/mcp/{userId}/servers/{serverId}/status", request, currentUserId(), serverId);
    }

    @PostMapping("/mcp/servers/{serverId}/health-check")
    Map<String, Object> checkMcpServer(@PathVariable long serverId) {
        return aiServiceClient.postJson("/ai/workbench/mcp/{userId}/servers/{serverId}/health-check", Map.of(), currentUserId(), serverId);
    }

    @GetMapping("/mcp/tools")
    Map<String, Object> mcpTools(@RequestParam(required = false) Long serverId) {
        String path = serverId == null
                ? "/ai/workbench/mcp/{userId}/tools"
                : "/ai/workbench/mcp/{userId}/tools?serverId={serverId}";
        return serverId == null
                ? aiServiceClient.getJson(path, currentUserId())
                : aiServiceClient.getJson(path, currentUserId(), serverId);
    }

    @PostMapping("/mcp/servers/{serverId}/tools/sync")
    Map<String, Object> syncMcpTools(@PathVariable long serverId, @RequestBody Map<String, Object> request) {
        return aiServiceClient.postJson("/ai/workbench/mcp/{userId}/servers/{serverId}/tools/sync", request, currentUserId(), serverId);
    }

    @PatchMapping("/mcp/tools/{toolId}/policy")
    Map<String, Object> updateMcpToolPolicy(@PathVariable long toolId, @RequestBody Map<String, Object> request) {
        return aiServiceClient.patchJson("/ai/workbench/mcp/{userId}/tools/{toolId}/policy", request, currentUserId(), toolId);
    }

    @PostMapping("/mcp/tools/{toolId}/invoke")
    Map<String, Object> invokeMcpTool(@PathVariable long toolId, @RequestBody Map<String, Object> request) {
        return aiServiceClient.postJson("/ai/workbench/mcp/{userId}/tools/{toolId}/invoke", request, currentUserId(), toolId);
    }

    @GetMapping("/workspaces")
    Map<String, Object> workspaces() {
        return aiServiceClient.getJson("/ai/workbench/workspaces/{userId}", currentUserId());
    }

    @PostMapping("/workspaces")
    Map<String, Object> createWorkspace(@RequestBody Map<String, Object> request) {
        return aiServiceClient.postJson("/ai/workbench/workspaces/{userId}", request, currentUserId());
    }

    @GetMapping("/workspaces/{workspaceId}/policies")
    Map<String, Object> workspacePolicies(@PathVariable long workspaceId) {
        return aiServiceClient.getJson("/ai/workbench/workspaces/{userId}/{workspaceId}/policies", currentUserId(), workspaceId);
    }

    @PostMapping("/workspaces/{workspaceId}/policies")
    Map<String, Object> createWorkspacePolicy(@PathVariable long workspaceId, @RequestBody Map<String, Object> request) {
        return aiServiceClient.postJson("/ai/workbench/workspaces/{userId}/{workspaceId}/policies", request, currentUserId(), workspaceId);
    }

    @DeleteMapping("/workspaces/{workspaceId}/policies/{policyId}")
    Map<String, Object> deleteWorkspacePolicy(@PathVariable long workspaceId, @PathVariable long policyId) {
        return aiServiceClient.deleteJson(
                "/ai/workbench/workspaces/{userId}/{workspaceId}/policies/{policyId}",
                currentUserId(), workspaceId, policyId
        );
    }

    @PostMapping("/workspaces/{workspaceId}/commands/preview")
    Map<String, Object> previewCommand(@PathVariable long workspaceId, @RequestBody Map<String, Object> request) {
        return aiServiceClient.postJson("/ai/workbench/workspaces/{userId}/{workspaceId}/commands/preview", request, currentUserId(), workspaceId);
    }

    @PostMapping("/workspaces/{workspaceId}/commands/run")
    Map<String, Object> runCommand(@PathVariable long workspaceId, @RequestBody Map<String, Object> request) {
        return aiServiceClient.postJson("/ai/workbench/workspaces/{userId}/{workspaceId}/commands/run", request, currentUserId(), workspaceId);
    }

    @GetMapping("/workspaces/{workspaceId}/commands")
    Map<String, Object> commandRuns(@PathVariable long workspaceId, @RequestParam(defaultValue = "50") int limit) {
        return aiServiceClient.getJson("/ai/workbench/workspaces/{userId}/{workspaceId}/commands?limit={limit}", currentUserId(), workspaceId, limit);
    }

    @GetMapping("/workflows")
    Map<String, Object> workflows() {
        return aiServiceClient.getJson("/ai/workbench/workflows/{userId}", currentUserId());
    }

    @PostMapping("/workflows")
    Map<String, Object> createWorkflow(@RequestBody Map<String, Object> request) {
        return aiServiceClient.postJson("/ai/workbench/workflows/{userId}", request, currentUserId());
    }

    @PostMapping("/workflows/from-plan/{planId}")
    Map<String, Object> createWorkflowFromPlan(@PathVariable long planId) {
        return aiServiceClient.postJson(
                "/ai/workbench/workflows/{userId}/from-plan/{planId}", Map.of(), currentUserId(), planId
        );
    }

    @GetMapping("/workflows/{workflowId}")
    Map<String, Object> workflow(@PathVariable long workflowId) {
        return aiServiceClient.getJson("/ai/workbench/workflows/{userId}/{workflowId}", currentUserId(), workflowId);
    }

    @PatchMapping("/workflows/{workflowId}")
    Map<String, Object> updateWorkflow(@PathVariable long workflowId, @RequestBody Map<String, Object> request) {
        return aiServiceClient.patchJson("/ai/workbench/workflows/{userId}/{workflowId}", request, currentUserId(), workflowId);
    }

    @PostMapping("/workflows/{workflowId}/runs")
    Map<String, Object> runWorkflow(@PathVariable long workflowId, @RequestBody Map<String, Object> request) {
        return aiServiceClient.postJson("/ai/workbench/workflows/{userId}/{workflowId}/runs", request, currentUserId(), workflowId);
    }

    @GetMapping("/workflows/{workflowId}/runs")
    Map<String, Object> workflowRuns(@PathVariable long workflowId, @RequestParam(defaultValue = "50") int limit) {
        return aiServiceClient.getJson("/ai/workbench/workflows/{userId}/{workflowId}/runs?limit={limit}", currentUserId(), workflowId, limit);
    }

    @GetMapping("/workflow-runs")
    Map<String, Object> workflowRuns(@RequestParam(defaultValue = "50") int limit) {
        return aiServiceClient.getJson("/ai/workbench/workflow-runs/{userId}?limit={limit}", currentUserId(), limit);
    }

    @GetMapping("/workflow-runs/{runId}")
    Map<String, Object> workflowRun(@PathVariable long runId) {
        return aiServiceClient.getJson("/ai/workbench/workflow-runs/{userId}/{runId}", currentUserId(), runId);
    }

    @PostMapping("/workflow-runs/{runId}/resume")
    Map<String, Object> resumeWorkflowRun(@PathVariable long runId, @RequestBody Map<String, Object> request) {
        return aiServiceClient.postJson("/ai/workbench/workflow-runs/{userId}/{runId}/resume", request, currentUserId(), runId);
    }

    @PostMapping("/workflow-runs/{runId}/retry")
    Map<String, Object> retryWorkflowRun(@PathVariable long runId, @RequestBody Map<String, Object> request) {
        return aiServiceClient.postJson("/ai/workbench/workflow-runs/{userId}/{runId}/retry", request, currentUserId(), runId);
    }

    @PostMapping("/workflow-runs/{runId}/cancel")
    Map<String, Object> cancelWorkflowRun(@PathVariable long runId) {
        return aiServiceClient.postJson("/ai/workbench/workflow-runs/{userId}/{runId}/cancel", Map.of(), currentUserId(), runId);
    }

    @GetMapping("/automations")
    Map<String, Object> automations() {
        return aiServiceClient.getJson("/ai/workbench/automations/{userId}", currentUserId());
    }

    @PostMapping("/automations")
    Map<String, Object> createAutomation(@RequestBody Map<String, Object> request) {
        return aiServiceClient.postJson("/ai/workbench/automations/{userId}", request, currentUserId());
    }

    @PatchMapping("/automations/{automationId}")
    Map<String, Object> updateAutomation(@PathVariable long automationId, @RequestBody Map<String, Object> request) {
        return aiServiceClient.patchJson("/ai/workbench/automations/{userId}/{automationId}", request, currentUserId(), automationId);
    }

    @DeleteMapping("/automations/{automationId}")
    Map<String, Object> deleteAutomation(@PathVariable long automationId) {
        return aiServiceClient.deleteJson("/ai/workbench/automations/{userId}/{automationId}", currentUserId(), automationId);
    }

    @PostMapping("/automations/{automationId}/enable")
    Map<String, Object> enableAutomation(@PathVariable long automationId) {
        return aiServiceClient.postJson("/ai/workbench/automations/{userId}/{automationId}/enable", Map.of(), currentUserId(), automationId);
    }

    @PostMapping("/automations/{automationId}/runs")
    Map<String, Object> runAutomation(@PathVariable long automationId, @RequestBody Map<String, Object> request) {
        return aiServiceClient.postJson("/ai/workbench/automations/{userId}/{automationId}/runs", request, currentUserId(), automationId);
    }

    @PostMapping("/automations/{automationId}/check-trigger")
    Map<String, Object> checkAutomationTrigger(@PathVariable long automationId) {
        return aiServiceClient.postJson(
                "/ai/workbench/automations/{userId}/{automationId}/check-trigger", Map.of(), currentUserId(), automationId
        );
    }

    @GetMapping("/automations/{automationId}/runs")
    Map<String, Object> automationRuns(@PathVariable long automationId, @RequestParam(defaultValue = "50") int limit) {
        return aiServiceClient.getJson("/ai/workbench/automations/{userId}/{automationId}/runs?limit={limit}", currentUserId(), automationId, limit);
    }

    @PostMapping("/automations/{automationId}/pause")
    Map<String, Object> pauseAutomation(@PathVariable long automationId) {
        return aiServiceClient.postJson("/ai/workbench/automations/{userId}/{automationId}/pause", Map.of(), currentUserId(), automationId);
    }

    @GetMapping("/audit")
    Map<String, Object> executionAudit(@RequestParam(defaultValue = "100") int limit) {
        return aiServiceClient.getJson("/ai/workbench/audit/{userId}?limit={limit}", currentUserId(), limit);
    }

    private String currentUserId() {
        return String.valueOf(CurrentUserUtils.currentUserId());
    }
}
