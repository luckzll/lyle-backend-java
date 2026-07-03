package com.example.aiapp.infrastructure.client;

import com.example.aiapp.domain.dto.AiChatRequestDTO;
import com.example.aiapp.domain.dto.AiChatResponseDTO;
import com.example.aiapp.domain.dto.AiIngestRequestDTO;
import com.example.aiapp.domain.dto.AiIngestResponseDTO;
import com.example.aiapp.domain.dto.AiIngestTaskResponseDTO;
import com.example.aiapp.domain.dto.BatchEvaluationRequestDTO;
import org.springframework.stereotype.Component;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class AiServiceClient {

    private final WebClient aiServiceWebClient;

    public AiServiceClient(WebClient aiServiceWebClient) {
        this.aiServiceWebClient = aiServiceWebClient;
    }

    public AiChatResponseDTO chat(AiChatRequestDTO request) {
        return aiServiceWebClient.post()
                .uri("/ai/chat")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(AiChatResponseDTO.class)
                .block();
    }

    public Flux<String> streamChat(AiChatRequestDTO request) {
        return aiServiceWebClient.post()
                .uri("/ai/chat/stream")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(org.springframework.core.io.buffer.DataBuffer.class)
                .map(dataBuffer -> {
                    try {
                        return dataBuffer.toString(StandardCharsets.UTF_8);
                    } finally {
                        DataBufferUtils.release(dataBuffer);
                    }
                });
    }

    public AiIngestResponseDTO ingest(AiIngestRequestDTO request) {
        return aiServiceWebClient.post()
                .uri("/ai/ingest")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(AiIngestResponseDTO.class)
                .block();
    }

    public AiIngestTaskResponseDTO getIngestTask(String taskId) {
        return aiServiceWebClient.get()
                .uri("/ai/ingest/tasks/{taskId}", taskId)
                .retrieve()
                .bodyToMono(AiIngestTaskResponseDTO.class)
                .block();
    }

    public Map<String, Object> getMemory(String userId, String conversationId) {
        return aiServiceWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/ai/memory/{userId}")
                        .queryParamIfPresent("conversationId", java.util.Optional.ofNullable(conversationId))
                        .build(userId))
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> upsertStructuredMemory(String userId, Map<String, Object> request) {
        return aiServiceWebClient.put()
                .uri("/ai/memory/{userId}/structured", userId)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public void deleteStructuredMemory(String userId, String key) {
        aiServiceWebClient.delete()
                .uri("/ai/memory/{userId}/structured/{key}", userId, key)
                .retrieve()
                .bodyToMono(Void.class)
                .block();
    }

    public void purgeStructuredMemory(String userId, String key) {
        aiServiceWebClient.delete()
                .uri("/ai/memory/{userId}/structured/{key}/purge", userId, key)
                .retrieve()
                .bodyToMono(Void.class)
                .block();
    }

    public Map<String, Object> changeStructuredMemoryStatus(String userId, String key, Map<String, String> request) {
        return aiServiceWebClient.put()
                .uri("/ai/memory/{userId}/structured/{key}/status", userId, key)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> getStructuredMemoryHistory(String userId, String key, int limit) {
        return aiServiceWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/ai/memory/{userId}/structured/{key}/history")
                        .queryParam("limit", limit)
                        .build(userId, key))
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> getStructuredMemoryMergeSuggestions(String userId) {
        return aiServiceWebClient.get()
                .uri("/ai/memory/{userId}/structured/merge-suggestions", userId)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> getMemoryProfiles(String userId, String status, int limit) {
        return aiServiceWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/ai/memory/{userId}/profiles")
                        .queryParamIfPresent("status", java.util.Optional.ofNullable(status))
                        .queryParam("limit", limit)
                        .build(userId))
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> getMemoryFragments(String userId, String status, String category, int limit) {
        return aiServiceWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/ai/memory/{userId}/fragments")
                        .queryParamIfPresent("status", java.util.Optional.ofNullable(status))
                        .queryParamIfPresent("category", java.util.Optional.ofNullable(category))
                        .queryParam("limit", limit)
                        .build(userId))
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> getProfileEvidence(String userId, long profileId, int limit) {
        return aiServiceWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/ai/memory/{userId}/profiles/{profileId}/evidence")
                        .queryParam("limit", limit)
                        .build(userId, profileId))
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> changeProfileStatus(String userId, String schemaPath, Map<String, String> request) {
        return aiServiceWebClient.patch()
                .uri(uriBuilder -> uriBuilder
                        .path("/ai/memory/{userId}/profiles/{schemaPath}/status")
                        .build(userId, schemaPath))
                .bodyValue(request)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> changeFragmentStatus(String userId, long fragmentId, Map<String, String> request) {
        return aiServiceWebClient.patch()
                .uri("/ai/memory/{userId}/fragments/{fragmentId}/status", userId, fragmentId)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> getMetrics() {
        return aiServiceWebClient.get()
                .uri("/ai/metrics")
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> getTools() {
        return aiServiceWebClient.get()
                .uri("/ai/tools")
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> getToolAudit(int limit) {
        return aiServiceWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/ai/tools/audit")
                        .queryParam("limit", limit)
                        .build())
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> getPendingOperations(String userId, int limit) {
        return aiServiceWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/ai/tools/operations/{userId}")
                        .queryParam("limit", limit)
                        .build(userId))
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> confirmPendingOperation(String userId, long operationId) {
        return aiServiceWebClient.post()
                .uri("/ai/tools/operations/{userId}/{operationId}/confirm", userId, operationId)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> rejectPendingOperation(String userId, long operationId) {
        return aiServiceWebClient.post()
                .uri("/ai/tools/operations/{userId}/{operationId}/reject", userId, operationId)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> getTodos(String userId, int limit) {
        return aiServiceWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/ai/tools/todos/{userId}")
                        .queryParam("limit", limit)
                        .build(userId))
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> createTodo(String userId, Map<String, Object> request) {
        return aiServiceWebClient.post()
                .uri("/ai/tools/todos/{userId}", userId)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> updateTodoStatus(String userId, long todoId, Map<String, String> request) {
        return aiServiceWebClient.patch()
                .uri("/ai/tools/todos/{userId}/{todoId}/status", userId, todoId)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> updateTodoDueAt(String userId, long todoId, Map<String, Object> request) {
        return aiServiceWebClient.patch()
                .uri("/ai/tools/todos/{userId}/{todoId}/due-at", userId, todoId)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public void deleteTodo(String userId, long todoId) {
        aiServiceWebClient.delete()
                .uri("/ai/tools/todos/{userId}/{todoId}", userId, todoId)
                .retrieve()
                .bodyToMono(Void.class)
                .block();
    }

    public Map<String, Object> getCalendarEvents(String userId, int limit) {
        return aiServiceWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/ai/tools/calendar/{userId}")
                        .queryParam("limit", limit)
                        .build(userId))
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> updateCalendarStatus(String userId, long eventId, Map<String, String> request) {
        return aiServiceWebClient.patch()
                .uri("/ai/tools/calendar/{userId}/{eventId}/status", userId, eventId)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public void deleteCalendarEvent(String userId, long eventId) {
        aiServiceWebClient.delete()
                .uri("/ai/tools/calendar/{userId}/{eventId}", userId, eventId)
                .retrieve()
                .bodyToMono(Void.class)
                .block();
    }

    public Map<String, Object> getReminders(String userId, int limit) {
        return aiServiceWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/ai/tools/reminders/{userId}")
                        .queryParam("limit", limit)
                        .build(userId))
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> markReminderRead(String userId, long notificationId) {
        return aiServiceWebClient.patch()
                .uri("/ai/tools/reminders/{userId}/{notificationId}/read", userId, notificationId)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> evaluateBatch(BatchEvaluationRequestDTO request) {
        return aiServiceWebClient.post()
                .uri("/ai/evaluate/batch")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> getEvaluationRuns(int limit) {
        return aiServiceWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/ai/evaluate/runs")
                        .queryParam("limit", limit)
                        .build())
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> getEvaluationRun(long runId) {
        return aiServiceWebClient.get()
                .uri("/ai/evaluate/runs/{runId}", runId)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> getEvaluationRunReport(long runId) {
        return aiServiceWebClient.get()
                .uri("/ai/evaluate/runs/{runId}/report", runId)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> getEvaluationTrends(int limit) {
        return aiServiceWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/ai/evaluate/trends")
                        .queryParam("limit", limit)
                        .build())
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> getEvaluationSets(int limit) {
        return aiServiceWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/ai/evaluate/sets")
                        .queryParam("limit", limit)
                        .build())
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> saveEvaluationSet(Map<String, Object> request) {
        return aiServiceWebClient.post()
                .uri("/ai/evaluate/sets")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> updateEvaluationSet(long setId, Map<String, Object> request) {
        return aiServiceWebClient.put()
                .uri("/ai/evaluate/sets/{setId}", setId)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public void deleteEvaluationSet(long setId) {
        aiServiceWebClient.delete()
                .uri("/ai/evaluate/sets/{setId}", setId)
                .retrieve()
                .bodyToMono(Void.class)
                .block();
    }

    public Map<String, Object> getEvaluationSet(long setId) {
        return aiServiceWebClient.get()
                .uri("/ai/evaluate/sets/{setId}", setId)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> runEvaluationSet(long setId, boolean badcasesOnly) {
        return aiServiceWebClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/ai/evaluate/sets/{setId}/run")
                        .queryParam("badcasesOnly", badcasesOnly)
                        .build(setId))
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> getPromptTemplates(int limit) {
        return aiServiceWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/ai/prompts")
                        .queryParam("limit", limit)
                        .build())
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> savePromptTemplate(Map<String, Object> request) {
        return aiServiceWebClient.post()
                .uri("/ai/prompts")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> activatePromptTemplate(long promptId) {
        return aiServiceWebClient.post()
                .uri("/ai/prompts/{promptId}/activate", promptId)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> debugRag(Map<String, Object> request) {
        return aiServiceWebClient.post()
                .uri("/ai/rag/debug")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> getRagChunks(String knowledgeBaseId, int limit) {
        return aiServiceWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/ai/rag/chunks")
                        .queryParamIfPresent("knowledgeBaseId", java.util.Optional.ofNullable(knowledgeBaseId))
                        .queryParam("limit", limit)
                        .build())
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }
}
