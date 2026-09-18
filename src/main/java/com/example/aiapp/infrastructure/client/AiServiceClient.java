package com.example.aiapp.infrastructure.client;

import com.example.aiapp.domain.dto.AiChatRequestDTO;
import com.example.aiapp.domain.dto.AiChatResponseDTO;
import com.example.aiapp.domain.dto.AiIngestRequestDTO;
import com.example.aiapp.domain.dto.AiIngestResponseDTO;
import com.example.aiapp.domain.dto.AiIngestTaskResponseDTO;
import com.example.aiapp.domain.dto.BatchEvaluationRequestDTO;
import org.springframework.stereotype.Component;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
        return Flux.defer(() -> {
            StreamingUtf8Decoder decoder = new StreamingUtf8Decoder();
            return aiServiceWebClient.post()
                .uri("/ai/chat/stream")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(org.springframework.core.io.buffer.DataBuffer.class)
                .map(dataBuffer -> {
                    try {
                        byte[] bytes = new byte[dataBuffer.readableByteCount()];
                        dataBuffer.read(bytes);
                        return decoder.decode(bytes, false);
                    } finally {
                        DataBufferUtils.release(dataBuffer);
                    }
                })
                .concatWith(Flux.defer(() -> Flux.just(decoder.decode(new byte[0], true))))
                .filter(chunk -> !chunk.isEmpty())
                .doOnDiscard(org.springframework.core.io.buffer.DataBuffer.class, DataBufferUtils::release);
        });
    }

    public Flux<String> streamLocalWorkspace(Map<String, Object> request) {
        return Flux.defer(() -> {
            StreamingUtf8Decoder decoder = new StreamingUtf8Decoder();
            return aiServiceWebClient.post().uri("/ai/local-workspace/step/stream")
                    .accept(MediaType.TEXT_EVENT_STREAM).bodyValue(request).retrieve()
                    .onStatus(HttpStatusCode::isError, response -> response.releaseBody().then(
                            reactor.core.publisher.Mono.just(new IllegalStateException("Workspace model unavailable"))))
                    .bodyToFlux(org.springframework.core.io.buffer.DataBuffer.class)
                    .map(buffer -> {
                        try {
                            byte[] bytes = new byte[buffer.readableByteCount()]; buffer.read(bytes);
                            return decoder.decode(bytes, false);
                        } finally { DataBufferUtils.release(buffer); }
                    })
                    .concatWith(Flux.defer(() -> Flux.just(decoder.decode(new byte[0], true))))
                    .filter(chunk -> !chunk.isEmpty())
                    .doOnDiscard(org.springframework.core.io.buffer.DataBuffer.class, DataBufferUtils::release);
        });
    }

    /** Network buffers may split a UTF-8 code point, so decoding must span buffers. */
    private static final class StreamingUtf8Decoder {
        private final java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
        private byte[] pending = new byte[0];

        String decode(byte[] bytes, boolean end) {
            java.nio.ByteBuffer input = java.nio.ByteBuffer.allocate(pending.length + bytes.length);
            input.put(pending).put(bytes).flip();
            java.nio.CharBuffer output = java.nio.CharBuffer.allocate(input.remaining() + 1);
            java.nio.charset.CoderResult result = decoder.decode(input, output, end);
            if (result.isError()) {
                throw new IllegalStateException("Invalid UTF-8 in AI stream");
            }
            pending = new byte[input.remaining()];
            input.get(pending);
            output.flip();
            return output.toString();
        }
    }

    public AiIngestResponseDTO ingest(AiIngestRequestDTO request) {
        return aiServiceWebClient.post()
                .uri("/ai/ingest")
                .bodyValue(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::serviceError)
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

    public Map<String, Object> getJson(String path, Object... uriVariables) {
        return aiServiceWebClient.get()
                .uri(path, uriVariables)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::serviceError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> postJson(String path, Map<String, Object> request, Object... uriVariables) {
        return aiServiceWebClient.post()
                .uri(path, uriVariables)
                .bodyValue(request == null ? Map.of() : request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::serviceError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> postJsonWithTimeout(String path, Map<String, Object> request, Duration timeout) {
        Duration bounded = timeout == null || timeout.isNegative() || timeout.isZero()
                ? Duration.ofSeconds(1)
                : timeout;
        return aiServiceWebClient.post()
                .uri(path)
                .bodyValue(request == null ? Map.of() : request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::serviceError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                })
                .timeout(bounded)
                .block();
    }

    public Map<String, Object> patchJson(String path, Map<String, Object> request, Object... uriVariables) {
        return aiServiceWebClient.patch()
                .uri(path, uriVariables)
                .bodyValue(request == null ? Map.of() : request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::serviceError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> deleteJson(String path, Object... uriVariables) {
        return aiServiceWebClient.delete()
                .uri(path, uriVariables)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::serviceError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    private reactor.core.publisher.Mono<? extends Throwable> serviceError(
            org.springframework.web.reactive.function.client.ClientResponse response
    ) {
        return response.bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                })
                .defaultIfEmpty(Map.of())
                .map(body -> new ResponseStatusException(
                        response.statusCode(),
                        String.valueOf(body.getOrDefault("detail", "AI 服务请求失败"))
                ));
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

    public Map<String, Object> getMemoryRecords(
            String userId,
            String status,
            String query,
            int limit
    ) {
        return aiServiceWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/ai/memory/{userId}/records")
                        .queryParamIfPresent("status", java.util.Optional.ofNullable(status))
                        .queryParamIfPresent("query", java.util.Optional.ofNullable(query))
                        .queryParam("limit", limit)
                        .build(userId))
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::serviceError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> getMemoryRecord(String userId, String memoryId) {
        return getJson("/ai/memory/{userId}/records/{memoryId}", userId, memoryId);
    }

    public Map<String, Object> getMemoryRecordSources(String userId, String memoryId) {
        return getJson("/ai/memory/{userId}/records/{memoryId}/sources", userId, memoryId);
    }

    public Map<String, Object> getMemoryInbox(String userId) {
        return getJson("/ai/memory/{userId}/inbox", userId);
    }

    public Map<String, Object> getMemorySchema(String userId) {
        return getJson("/ai/memory/{userId}/schema", userId);
    }

    public Map<String, Object> getMemoryFacts(String userId) {
        return getJson("/ai/memory/{userId}/facts", userId);
    }

    public Map<String, Object> reviseMemoryFact(String userId, String factId, Map<String, Object> request) {
        return aiServiceWebClient.put()
                .uri("/ai/memory/{userId}/facts/{factId}", userId, factId)
                .bodyValue(request).retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {}).block();
    }

    public Map<String, Object> rebuildMemorySchema(String userId) {
        return postJson("/ai/memory/{userId}/schema/rebuild", Map.of(), userId);
    }

    public Map<String, Object> recallMemorySchema(String userId, Map<String, Object> request) {
        return postJson("/ai/memory/{userId}/schema/recall", request, userId);
    }

    public Map<String, Object> resolveMemoryConflict(
            String userId,
            long conflictId,
            Map<String, Object> request
    ) {
        return postJson(
                "/ai/memory/{userId}/conflicts/{conflictId}:resolve",
                request,
                userId,
                conflictId
        );
    }

    public Map<String, Object> governMemoryRecord(
            String userId,
            String memoryId,
            String action,
            Map<String, Object> request
    ) {
        return postJson(
                "/ai/memory/{userId}/records/{memoryId}:" + action,
                request,
                userId,
                memoryId
        );
    }

    public Map<String, Object> getMemoryPurgeJob(String userId, long jobId) {
        return getJson("/ai/memory/{userId}/purge-jobs/{jobId}", userId, jobId);
    }

    public Map<String, Object> retryMemoryPurgeJob(String userId, long jobId) {
        return postJson("/ai/memory/{userId}/purge-jobs/{jobId}:retry", Map.of(), userId, jobId);
    }

    public Map<String, Object> getMemoryGovernanceEvents(String userId) {
        return getJson("/ai/memory/{userId}/governance-events", userId);
    }

    public Map<String, Object> listProjects(String userId, String status, int limit) {
        return aiServiceWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/ai/projects/{userId}")
                        .queryParamIfPresent("status", java.util.Optional.ofNullable(status))
                        .queryParam("limit", limit)
                        .build(userId))
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> createProject(String userId, Map<String, Object> request) {
        return aiServiceWebClient.post()
                .uri("/ai/projects/{userId}", userId)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> getProject(String userId, long projectId) {
        return aiServiceWebClient.get()
                .uri("/ai/projects/{userId}/{projectId}", userId, projectId)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> updateProject(String userId, long projectId, Map<String, Object> request) {
        return aiServiceWebClient.patch()
                .uri("/ai/projects/{userId}/{projectId}", userId, projectId)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> getProjectOverview(String userId, long projectId) {
        return aiServiceWebClient.get()
                .uri("/ai/projects/{userId}/{projectId}/overview", userId, projectId)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> listProjectLinks(String userId, long projectId, String targetType, int limit) {
        return aiServiceWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/ai/projects/{userId}/{projectId}/links")
                        .queryParamIfPresent("targetType", java.util.Optional.ofNullable(targetType))
                        .queryParam("limit", limit)
                        .build(userId, projectId))
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> createProjectLink(String userId, long projectId, Map<String, Object> request) {
        return aiServiceWebClient.post()
                .uri("/ai/projects/{userId}/{projectId}/links", userId, projectId)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public void deleteProjectLink(String userId, long projectId, long linkId) {
        aiServiceWebClient.delete()
                .uri("/ai/projects/{userId}/{projectId}/links/{linkId}", userId, projectId, linkId)
                .retrieve()
                .bodyToMono(Void.class)
                .block();
    }

    public Map<String, Object> createProjectDecision(String userId, long projectId, Map<String, Object> request) {
        return aiServiceWebClient.post()
                .uri("/ai/projects/{userId}/{projectId}/decisions", userId, projectId)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> createProjectOpenQuestion(String userId, long projectId, Map<String, Object> request) {
        return aiServiceWebClient.post()
                .uri("/ai/projects/{userId}/{projectId}/open-questions", userId, projectId)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> updateProjectOpenQuestion(String userId, long questionId, Map<String, Object> request) {
        return aiServiceWebClient.patch()
                .uri("/ai/projects/{userId}/open-questions/{questionId}", userId, questionId)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> listGoals(String userId, Long projectId, String status, int limit) {
        return aiServiceWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/ai/goals/{userId}")
                        .queryParamIfPresent("projectId", java.util.Optional.ofNullable(projectId))
                        .queryParamIfPresent("status", java.util.Optional.ofNullable(status))
                        .queryParam("limit", limit)
                        .build(userId))
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> createGoal(String userId, Map<String, Object> request) {
        return aiServiceWebClient.post()
                .uri("/ai/goals/{userId}", userId)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> getGoal(String userId, long goalId) {
        return aiServiceWebClient.get()
                .uri("/ai/goals/{userId}/{goalId}", userId, goalId)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> updateGoal(String userId, long goalId, Map<String, Object> request) {
        return aiServiceWebClient.patch()
                .uri("/ai/goals/{userId}/{goalId}", userId, goalId)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> listMilestones(String userId, long goalId) {
        return aiServiceWebClient.get()
                .uri("/ai/goals/{userId}/{goalId}/milestones", userId, goalId)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> createMilestone(String userId, long goalId, Map<String, Object> request) {
        return aiServiceWebClient.post()
                .uri("/ai/goals/{userId}/{goalId}/milestones", userId, goalId)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> linkTaskToGoal(String userId, long goalId, String taskId, Map<String, Object> request) {
        return aiServiceWebClient.post()
                .uri("/ai/goals/{userId}/{goalId}/tasks/{taskId}/link", userId, goalId, taskId)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> listPlans(String userId, Long projectId, String status, int limit) {
        return aiServiceWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/ai/plans/{userId}")
                        .queryParamIfPresent("projectId", java.util.Optional.ofNullable(projectId))
                        .queryParamIfPresent("status", java.util.Optional.ofNullable(status))
                        .queryParam("limit", limit)
                        .build(userId))
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> createPlan(String userId, Map<String, Object> request) {
        return aiServiceWebClient.post()
                .uri("/ai/plans/{userId}", userId)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> getPlan(String userId, long planId) {
        return aiServiceWebClient.get()
                .uri("/ai/plans/{userId}/{planId}", userId, planId)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> transitionPlan(String userId, long planId, String action) {
        return aiServiceWebClient.post()
                .uri("/ai/plans/{userId}/{planId}/{action}", userId, planId, action)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> getTodayBriefing(String userId, Long projectId) {
        return aiServiceWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/ai/briefings/{userId}/today")
                        .queryParamIfPresent("projectId", java.util.Optional.ofNullable(projectId))
                        .build(userId))
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> generateDailyBriefing(String userId, Long projectId) {
        return aiServiceWebClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/ai/briefings/{userId}/daily/generate")
                        .queryParamIfPresent("projectId", java.util.Optional.ofNullable(projectId))
                        .build(userId))
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> generateEveningBriefing(String userId, Long projectId) {
        return aiServiceWebClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/ai/briefings/{userId}/evening/generate")
                        .queryParamIfPresent("projectId", java.util.Optional.ofNullable(projectId))
                        .build(userId))
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> generateWeeklyBriefing(String userId, Long projectId) {
        return aiServiceWebClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/ai/briefings/{userId}/weekly/generate")
                        .queryParamIfPresent("projectId", java.util.Optional.ofNullable(projectId))
                        .build(userId))
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> listConnectors() {
        return aiServiceWebClient.get()
                .uri("/ai/connectors")
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> getConnectorStatus(String connectorName) {
        return aiServiceWebClient.get()
                .uri("/ai/connectors/{connectorName}/status", connectorName)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> listConnectorResources(String connectorName, String repo, String state, int limit) {
        return aiServiceWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/ai/connectors/{connectorName}/list")
                        .queryParamIfPresent("repo", java.util.Optional.ofNullable(repo))
                        .queryParamIfPresent("state", java.util.Optional.ofNullable(state))
                        .queryParam("limit", limit)
                        .build(connectorName))
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> searchConnector(String connectorName, String query, String repo, int limit) {
        return aiServiceWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/ai/connectors/{connectorName}/search")
                        .queryParam("q", query)
                        .queryParamIfPresent("repo", java.util.Optional.ofNullable(repo))
                        .queryParam("limit", limit)
                        .build(connectorName))
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> getConnectorResource(String connectorName, String resourceId) {
        return aiServiceWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/ai/connectors/{connectorName}/resources/{resourceId}")
                        .build(connectorName, resourceId))
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> previewConnectorCreate(String userId, String connectorName, Map<String, Object> request) {
        return aiServiceWebClient.post()
                .uri("/ai/connectors/{userId}/{connectorName}/preview-create", userId, connectorName)
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

    public Map<String, Object> getExperienceAnalytics(int days) {
        return aiServiceWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/ai/experience/analytics")
                        .queryParam("days", days)
                        .build())
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> getExperienceResults(
            String userId,
            String status,
            Long projectId,
            Long automationId,
            boolean attentionOnly,
            String cursor,
            int limit
    ) {
        return aiServiceWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/ai/experience/execution/{userId}/results")
                        .queryParam("limit", limit)
                        .queryParamIfPresent(
                                "status",
                                java.util.Optional.ofNullable(status).filter(value -> !value.isBlank())
                        )
                        .queryParamIfPresent("projectId", java.util.Optional.ofNullable(projectId))
                        .queryParamIfPresent("automationId", java.util.Optional.ofNullable(automationId))
                        .queryParam("attentionOnly", attentionOnly)
                        .queryParamIfPresent(
                                "cursor",
                                java.util.Optional.ofNullable(cursor).filter(value -> !value.isBlank())
                        )
                        .build(userId))
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::serviceError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
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
                .onStatus(HttpStatusCode::isError, this::serviceError)
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> createTodo(String userId, Map<String, Object> request) {
        return aiServiceWebClient.post()
                .uri("/ai/tools/todos/{userId}", userId)
                .bodyValue(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::serviceError)
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> updateTodoStatus(String userId, long todoId, Map<String, String> request) {
        return aiServiceWebClient.patch()
                .uri("/ai/tools/todos/{userId}/{todoId}/status", userId, todoId)
                .bodyValue(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::serviceError)
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public Map<String, Object> updateTodoDueAt(String userId, long todoId, Map<String, Object> request) {
        return aiServiceWebClient.patch()
                .uri("/ai/tools/todos/{userId}/{todoId}/due-at", userId, todoId)
                .bodyValue(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::serviceError)
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    public void deleteTodo(String userId, long todoId) {
        aiServiceWebClient.delete()
                .uri("/ai/tools/todos/{userId}/{todoId}", userId, todoId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::serviceError)
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

    public Map<String, Object> getRagChunksScoped(Map<String, Object> request) {
        return aiServiceWebClient.post()
                .uri("/ai/rag/chunks")
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
