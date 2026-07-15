package com.example.aiapp.agenttask.infrastructure.python;

import com.example.aiapp.infrastructure.client.AiServiceClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class AgentDecisionClient {

    private final AiServiceClient aiServiceClient;

    public AgentDecisionClient(AiServiceClient aiServiceClient) {
        this.aiServiceClient = aiServiceClient;
    }

    public Map<String, Object> plan(Map<String, Object> request) {
        return aiServiceClient.postJson("/internal/agent/plan", request);
    }

    public Map<String, Object> executeStep(Map<String, Object> request, Duration timeout) {
        return aiServiceClient.postJsonWithTimeout("/internal/agent/execute-step", request, timeout);
    }

    public Map<String, Object> nextAction(Map<String, Object> request) {
        return aiServiceClient.postJson("/internal/agent/next-action", request);
    }

    public Map<String, Object> reviewResult(Map<String, Object> request) {
        return aiServiceClient.postJson("/internal/agent/review-result", request);
    }

    public Map<String, Object> createChangePreview(Map<String, Object> request) {
        return aiServiceClient.postJson("/internal/agent/change-preview", request);
    }

    public Map<String, Object> operationStatus(long userId, long operationId) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("userId", String.valueOf(userId));
        request.put("operationId", operationId);
        return aiServiceClient.postJson("/internal/agent/operation-status", request);
    }

    public Map<String, Object> resumeCheck(Map<String, Object> request) {
        return aiServiceClient.postJson("/internal/agent/resume-check", request);
    }
}
