package com.example.aiapp.mission.infrastructure.python;

import com.example.aiapp.infrastructure.client.AiServiceClient;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class MissionAiClient {

    private final AiServiceClient aiServiceClient;

    public MissionAiClient(AiServiceClient aiServiceClient) {
        this.aiServiceClient = aiServiceClient;
    }

    public Map<String, Object> buildDraft(Map<String, Object> request) {
        return aiServiceClient.postJson("/internal/mission/draft", request);
    }

    public Map<String, Object> shadowScenarios(Map<String, Object> request) {
        return aiServiceClient.postJson("/internal/mission/shadow-scenarios", request);
    }

    public Map<String, Object> compensate(Map<String, Object> request) {
        return aiServiceClient.postJson("/internal/mission/compensate", request);
    }

    public Map<String, Object> digest(Map<String, Object> request) {
        return aiServiceClient.postJson("/internal/mission/digest", request);
    }
}
