package com.example.aiapp.service.impl;

import com.example.aiapp.billing.BillingService;
import com.example.aiapp.domain.dto.AiChatRequestDTO;
import com.example.aiapp.domain.dto.AiChatResponseDTO;
import com.example.aiapp.domain.dto.AiChatAttachmentDTO;
import com.example.aiapp.domain.dto.ChatRequestDTO;
import com.example.aiapp.domain.entity.ChatMessageEntity;
import com.example.aiapp.domain.entity.ConversationEntity;
import com.example.aiapp.domain.vo.ChatMessageVO;
import com.example.aiapp.domain.vo.ChatResponseVO;
import com.example.aiapp.domain.vo.SourceVO;
import com.example.aiapp.infrastructure.client.AiServiceClient;
import com.example.aiapp.mapper.ChatMessageMapper;
import com.example.aiapp.mapper.ConversationMapper;
import com.example.aiapp.mapper.KnowledgeBaseMapper;
import com.example.aiapp.service.AiConfigService;
import com.example.aiapp.service.ChatAttachmentService;
import com.example.aiapp.service.ChatService;
import com.example.aiapp.utils.StringUtils;
import com.example.aiapp.usage.UsageService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * AI 对话编排服务实现。
 */
@Service
public class ChatServiceImpl implements ChatService {

    private static final java.util.regex.Pattern DATA_IMAGE_PATTERN = java.util.regex.Pattern.compile(
            "data:image/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=\\r\\n]+"
    );
    private static final Pattern IMAGE_URL_PATTERN = Pattern.compile(
            "https?://\\S+\\.(?:png|jpe?g|webp|gif)(?:\\?\\S*)?",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SSE_FRAME_SEPARATOR = Pattern.compile("\\r?\\n\\r?\\n");

    private final AiServiceClient aiServiceClient;
    private final AiConfigService aiConfigService;
    private final ChatMessageMapper chatMessageMapper;
    private final ConversationMapper conversationMapper;
    private final ObjectMapper objectMapper;
    private final ChatDebugMetadataSanitizer chatDebugMetadataSanitizer;
    private ChatAttachmentService chatAttachmentService;
    private KnowledgeBaseMapper knowledgeBaseMapper;
    private KnowledgeRetrievalScopeService knowledgeRetrievalScopeService;
    private UsageService usageService;
    private BillingService billingService;
    private final ConcurrentMap<String, ActiveTurn> activeTurns = new ConcurrentHashMap<>();

    public ChatServiceImpl(
            AiServiceClient aiServiceClient,
            AiConfigService aiConfigService,
            ChatMessageMapper chatMessageMapper,
            ConversationMapper conversationMapper,
            ObjectMapper objectMapper
    ) {
        this.aiServiceClient = aiServiceClient;
        this.aiConfigService = aiConfigService;
        this.chatMessageMapper = chatMessageMapper;
        this.conversationMapper = conversationMapper;
        this.objectMapper = objectMapper;
        this.chatDebugMetadataSanitizer = new ChatDebugMetadataSanitizer(objectMapper);
    }

    @Autowired
    void setChatAttachmentService(ChatAttachmentService chatAttachmentService) {
        this.chatAttachmentService = chatAttachmentService;
    }

    @Autowired
    void setKnowledgeBaseMapper(KnowledgeBaseMapper knowledgeBaseMapper) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
    }

    @Autowired
    void setKnowledgeRetrievalScopeService(KnowledgeRetrievalScopeService service) {
        this.knowledgeRetrievalScopeService = service;
    }

    @Autowired
    void setUsageService(UsageService usageService) {
        this.usageService = usageService;
    }

    @Autowired
    void setBillingService(BillingService billingService) {
        this.billingService = billingService;
    }

    @Override
    public ChatResponseVO send(Long userId, ChatRequestDTO request) {
        ensureKnowledgeBasesOwnedByUser(userId, request.knowledgeBaseIds());
        Map<String, Object> knowledgeScope = knowledgeRetrievalScopeService == null ? Map.of()
                : knowledgeRetrievalScopeService.build(userId, request.knowledgeBaseIds(), false);
        String conversationId = StringUtils.isBlank(request.conversationId())
                ? UUID.randomUUID().toString()
                : request.conversationId();
        String turnId = StringUtils.isBlank(request.turnId())
                ? UUID.randomUUID().toString()
                : request.turnId();
        ensureConversationOwnedByUserIfPersisted(userId, conversationId);
        if (!StringUtils.isBlank(request.turnId())) {
            ChatMessageEntity existing = chatMessageMapper.selectByConversationIdAndTurnId(conversationId, turnId);
            if (existing != null) {
                return existingResponse(conversationId, existing);
            }
        }

        String userMessage = normalizedMessage(request.message(), request.attachmentIds());
        var attachments = chatAttachmentService == null
                ? List.<com.example.aiapp.domain.dto.AiChatAttachmentDTO>of()
                : chatAttachmentService.requireReady(userId, request.attachmentIds());

        String aiMessage = withRecentImageContextUnlessCurrentImage(conversationId, userMessage, attachments);
        UsageService.Reservation reservation = usageService == null
                ? null
                : usageService.reserve(userId, conversationId, turnId);
        String commercialReservation;
        try {
            commercialReservation = billingService == null
                    ? null
                    : billingService.reserveCommercial(userId, turnId);
        } catch (RuntimeException exception) {
            if (usageService != null && reservation != null) {
                usageService.releaseReservation(reservation.reservationId());
            }
            throw exception;
        }
        ChatMessageEntity userEntity = saveMessage(conversationId, turnId, "user", historyContent(userMessage), Map.of());
        if (chatAttachmentService != null) {
            chatAttachmentService.bind(userId, conversationId, userEntity.getId(), request.attachmentIds());
        }
        AiChatRequestDTO aiRequest = new AiChatRequestDTO(
                String.valueOf(userId),
                conversationId,
                turnId,
                request.projectId(),
                aiMessage,
                request.knowledgeBaseIds() == null ? List.of() : request.knowledgeBaseIds(),
                false,
                ChatModelCatalog.apply(request.modelId(), aiConfigService.currentConfigMap()),
                attachments,
                reservation == null ? null : reservation.traceId(),
                reservation == null ? null : reservation.reservationId(),
                usageBudget(reservation),
                String.valueOf(userEntity.getId()),
                knowledgeScope
        );
        AiChatResponseDTO aiResponse;
        try {
            aiResponse = aiServiceClient.chat(aiRequest);
        } finally {
            if (usageService != null && reservation != null) {
                usageService.releaseReservation(reservation.reservationId());
            }
            if (billingService != null) {
                billingService.releaseCommercialReservation(commercialReservation);
            }
        }
        Map<String, Object> assistantMetadata = new LinkedHashMap<>();
        assistantMetadata.put("turnId", turnId);
        assistantMetadata.put("phase", "COMPLETED");
        assistantMetadata.put("statusText", "回复完成");
        Map<String, Object> safeDebug = chatDebugMetadataSanitizer.sanitize(
                debugPayload(aiResponse, request.knowledgeBaseIds())
        );
        assistantMetadata.putAll(safeDebug);
        saveMessage(conversationId, turnId, "assistant", aiResponse.answer(), assistantMetadata);
        return new ChatResponseVO(
                conversationId,
                aiResponse.answer(),
                sourceList(safeDebug.get("sources")),
                mapList(safeDebug.get("agentTrace")),
                mapList(safeDebug.get("toolResults")),
                aiResponse.candidateMemories() == null ? List.of() : aiResponse.candidateMemories(),
                aiResponse.candidateTasks() == null ? List.of() : aiResponse.candidateTasks(),
                aiResponse.candidateGoals() == null ? List.of() : aiResponse.candidateGoals(),
                aiResponse.candidateDecisions() == null ? List.of() : aiResponse.candidateDecisions(),
                aiResponse.candidateOpenQuestions() == null ? List.of() : aiResponse.candidateOpenQuestions(),
                aiResponse.candidatePlans() == null ? List.of() : aiResponse.candidatePlans(),
                aiResponse.executionSuggestions() == null ? List.of() : aiResponse.executionSuggestions(),
                aiResponse.projectContext() == null ? java.util.Map.of() : aiResponse.projectContext(),
                mapValue(safeDebug.get("review")),
                usageFromMetadata(safeDebug),
                mapList(safeDebug.get("usedMemories")),
                mapValue(safeDebug.get("memoryExclusions")),
                stringValue(safeDebug.getOrDefault("memoryExplanationVersion", "v1")),
                stringValue(safeDebug.get("debugVersion")),
                stringList(safeDebug.get("knowledgeBaseIds")),
                mapValue(safeDebug.get("retrievalScope")),
                Boolean.TRUE.equals(safeDebug.get("debugTruncated"))
        );
    }

    @Override
    public void stream(Long userId, ChatRequestDTO request, Consumer<String> chunkConsumer) {
        ensureKnowledgeBasesOwnedByUser(userId, request.knowledgeBaseIds());
        Map<String, Object> knowledgeScope = knowledgeRetrievalScopeService == null ? Map.of()
                : knowledgeRetrievalScopeService.build(userId, request.knowledgeBaseIds(), false);
        String conversationId = StringUtils.isBlank(request.conversationId())
                ? UUID.randomUUID().toString()
                : request.conversationId();
        String turnId = StringUtils.isBlank(request.turnId())
                ? UUID.randomUUID().toString()
                : request.turnId();
        ensureConversationOwnedByUserIfPersisted(userId, conversationId);
        ChatMessageEntity existing = chatMessageMapper.selectByConversationIdAndTurnId(conversationId, turnId);
        boolean retrying = request.retry();
        if (retrying && existing == null) {
            throw new ResponseStatusException(NOT_FOUND, "要重试的对话轮次不存在");
        }
        if (retrying && !"FAILED".equals(stringValue(readMetadata(existing.getMetadataJson()).get("phase")))) {
            throw new ResponseStatusException(BAD_REQUEST, "只能重试失败或已停止的轮次");
        }
        if (existing != null && !retrying) {
            Map<String, Object> metadata = readMetadata(existing.getMetadataJson());
            if (!StringUtils.isBlank(existing.getContent())) {
                chunkConsumer.accept(sse("delta", Map.of("text", existing.getContent())));
            }
            chunkConsumer.accept(sse("meta", replayMetadata(metadata)));
            chunkConsumer.accept(sse("turn.status", Map.of(
                    "turnId", turnId,
                    "phase", stringValue(metadata.getOrDefault("phase", "COMPLETED")),
                    "message", stringValue(metadata.getOrDefault("statusText", "回复完成"))
            )));
            chunkConsumer.accept(sse("done", Map.of("turnId", turnId)));
            return;
        }

        ChatMessageEntity retryUser = retrying
                ? chatMessageMapper.selectUserByConversationIdAndTurnId(conversationId, turnId)
                : null;
        if (retrying && retryUser == null) {
            throw new ResponseStatusException(NOT_FOUND, "要重试的用户消息不存在");
        }
        String userMessage = retrying
                ? retryUser.getContent()
                : normalizedMessage(request.message(), request.attachmentIds());
        var attachments = chatAttachmentService == null
                ? List.<com.example.aiapp.domain.dto.AiChatAttachmentDTO>of()
                : retrying
                    ? chatAttachmentService.requireAttachedForRetry(
                            userId,
                            conversationId,
                            retryUser.getId(),
                            request.attachmentIds()
                    )
                    : chatAttachmentService.requireReady(userId, request.attachmentIds());

        String aiMessage = withRecentImageContextUnlessCurrentImage(conversationId, userMessage, attachments);
        UsageService.Reservation reservation = usageService == null
                ? null
                : usageService.reserve(userId, conversationId, turnId);
        String commercialReservation;
        try {
            commercialReservation = billingService == null
                    ? null
                    : billingService.reserveCommercial(userId, turnId);
        } catch (RuntimeException exception) {
            if (usageService != null && reservation != null) {
                usageService.releaseReservation(reservation.reservationId());
            }
            throw exception;
        }
        ChatMessageEntity sourceUserEntity;
        if (retrying) {
            sourceUserEntity = retryUser;
        } else {
            sourceUserEntity = saveMessage(conversationId, turnId, "user", historyContent(userMessage), Map.of());
            if (chatAttachmentService != null) {
                chatAttachmentService.bind(userId, conversationId, sourceUserEntity.getId(), request.attachmentIds());
            }
        }
        AiChatRequestDTO aiRequest = new AiChatRequestDTO(
                String.valueOf(userId),
                conversationId,
                turnId,
                request.projectId(),
                aiMessage,
                request.knowledgeBaseIds() == null ? List.of() : request.knowledgeBaseIds(),
                true,
                ChatModelCatalog.apply(request.modelId(), aiConfigService.currentConfigMap()),
                attachments,
                reservation == null ? null : reservation.traceId(),
                reservation == null ? null : reservation.reservationId(),
                usageBudget(reservation),
                String.valueOf(sourceUserEntity.getId()),
                knowledgeScope
        );
        StringBuilder assistantAnswer = new StringBuilder();
        StringBuilder sseBuffer = new StringBuilder();
        AtomicReference<String> upstreamPhase = new AtomicReference<>();
        AtomicBoolean upstreamDone = new AtomicBoolean();
        Map<String, Object> initialMetadata = new LinkedHashMap<>();
        initialMetadata.put("turnId", turnId);
        initialMetadata.put("phase", "THINKING");
        initialMetadata.put("statusText", "正在理解你的需求…");
        initialMetadata.put("processStartedAt", System.currentTimeMillis());
        initialMetadata.putAll(chatDebugMetadataSanitizer.sanitize(Map.of(
                "knowledgeBaseIds", request.knowledgeBaseIds() == null ? List.of() : request.knowledgeBaseIds()
        )));
        ChatMessageEntity assistantMessage;
        if (retrying) {
            assistantMessage = existing;
            assistantMessage.setContent("");
            assistantMessage.setMetadataJson(writeMetadata(initialMetadata));
            chatMessageMapper.updateById(assistantMessage);
            touchConversation(conversationId);
        } else {
            assistantMessage = saveMessage(
                    conversationId,
                    turnId,
                    "assistant",
                    "",
                    initialMetadata
            );
        }
        ActiveTurn activeTurn = new ActiveTurn(
                conversationId,
                turnId,
                assistantMessage,
                initialMetadata,
                Thread.currentThread()
        );
        activeTurns.put(turnId, activeTurn);
        chunkConsumer.accept(sse("turn.status", Map.of(
                "turnId", turnId,
                "phase", "THINKING",
                "message", "正在理解你的需求…",
                "processStartedAt", initialMetadata.get("processStartedAt")
        )));
        AtomicBoolean responding = new AtomicBoolean();
        AtomicBoolean textDirty = new AtomicBoolean();
        java.util.concurrent.atomic.AtomicLong lastTextSave = new java.util.concurrent.atomic.AtomicLong();
        try {
            aiServiceClient.streamChat(aiRequest)
                    .doOnNext(chunk -> {
                        if (activeTurn.cancelled().get()) {
                            throw new ChatTurnCancelledException();
                        }
                        boolean receivedDelta = appendStreamEvents(
                                assistantAnswer,
                                sseBuffer,
                                chunk,
                                upstreamPhase,
                                upstreamDone,
                                activeTurn,
                                event -> {
                                    synchronized (activeTurn) {
                                        activeTurn.message().setContent(assistantAnswer.toString());
                                        applyStreamEvent(activeTurn, event);
                                        if ("reasoning.delta".equals(event.name())) textDirty.set(true);
                                    }
                                },
                                chunkConsumer
                        );
                        String phase = upstreamPhase.get();
                        if (receivedDelta) textDirty.set(true);
                        if (receivedDelta
                                && responding.compareAndSet(false, true)
                                && (phase == null || "THINKING".equals(phase))) {
                            chunkConsumer.accept(sse("turn.status", Map.of(
                                    "turnId", turnId,
                                    "phase", "RESPONDING",
                                    "message", "正在回复…"
                            )));
                            synchronized (activeTurn) {
                                if (activeTurn.cancelled().get()) return;
                                activeTurn.metadata().put("phase", "RESPONDING");
                                activeTurn.metadata().put("statusText", "正在回复…");
                            }
                        }
                        // Keep a recoverable snapshot without issuing a database write per token.
                        long now = System.nanoTime();
                        if (textDirty.get() && (lastTextSave.get() == 0
                                || now - lastTextSave.get() >= 1_000_000_000L)) {
                            synchronized (activeTurn) {
                                if (activeTurn.cancelled().get()) return;
                                persistActiveTurn(activeTurn);
                            }
                            textDirty.set(false);
                            lastTextSave.set(now);
                        }
                    })
                    .blockLast();
            if (activeTurn.cancelled().get()) {
                return;
            }
            if (!upstreamDone.get()) {
                throw new IllegalStateException("AI stream ended without a completion event");
            }
            String finalUpstreamPhase = upstreamPhase.get();
            List<Map<String, Object>> unfinishedToolCalls;
            synchronized (activeTurn) {
                unfinishedToolCalls = interruptRunningToolCalls(activeTurn.metadata(), "执行已结束，未收到该工具的完成结果。");
                if (!"FAILED".equals(finalUpstreamPhase)) {
                    activeTurn.metadata().put("phase", "COMPLETED");
                    activeTurn.metadata().put("statusText", "回复完成");
                }
                activeTurn.message().setContent(assistantAnswer.toString());
                activeTurn.metadata().putIfAbsent("processCompletedAt", System.currentTimeMillis());
                persistActiveTurn(activeTurn);
            }
            unfinishedToolCalls.forEach(call -> chunkConsumer.accept(sse("tool.call", call)));
            if (!"FAILED".equals(finalUpstreamPhase)) {
                chunkConsumer.accept(sse("turn.status", Map.of(
                        "turnId", turnId,
                        "phase", "COMPLETED",
                        "message", "回复完成",
                        "processStartedAt", initialMetadata.get("processStartedAt"),
                        "processCompletedAt", initialMetadata.get("processCompletedAt")
                )));
            }
            // Completion is observable only after the final durable snapshot is saved.
            chunkConsumer.accept(sse("done", Map.of("turnId", turnId)));
        } catch (RuntimeException exception) {
            if (activeTurn.cancelled().get() || exception instanceof ChatTurnCancelledException) {
                return;
            }
            String failureMessage = streamFailureMessage(exception);
            List<Map<String, Object>> interruptedSteps;
            List<Map<String, Object>> interruptedToolCalls;
            synchronized (activeTurn) {
                activeTurn.metadata().put("phase", "FAILED");
                activeTurn.metadata().put("statusText", failureMessage);
                activeTurn.metadata().putIfAbsent("processCompletedAt", System.currentTimeMillis());
                failUnfinishedImageItems(activeTurn.metadata(), "图片任务已中断");
                interruptedSteps = interruptRunningSteps(activeTurn.metadata(), "已中断：" + failureMessage);
                interruptedToolCalls = interruptRunningToolCalls(activeTurn.metadata(), "连接已中断，未收到工具完成结果。");
                activeTurn.message().setContent(
                        assistantAnswer.isEmpty() ? failureMessage : assistantAnswer.toString()
                );
                persistActiveTurn(activeTurn);
            }
            interruptedSteps.forEach(step -> chunkConsumer.accept(sse("agent.step", step)));
            interruptedToolCalls.forEach(call -> chunkConsumer.accept(sse("tool.call", call)));
            chunkConsumer.accept(sse("turn.status", Map.of(
                    "turnId", turnId,
                    "phase", "FAILED",
                    "message", failureMessage,
                    "processStartedAt", initialMetadata.get("processStartedAt"),
                    "processCompletedAt", initialMetadata.get("processCompletedAt")
            )));
            chunkConsumer.accept(sse("error", Map.of("turnId", turnId, "message", failureMessage)));
            throw exception;
        } finally {
            activeTurns.remove(turnId, activeTurn);
            if (usageService != null && reservation != null) {
                usageService.releaseReservation(reservation.reservationId());
            }
            if (billingService != null) {
                billingService.releaseCommercialReservation(commercialReservation);
            }
        }
    }

    private Map<String, Long> usageBudget(UsageService.Reservation reservation) {
        if (reservation == null) {
            return Map.of();
        }
        return Map.of(
                "maxCalls", reservation.maxCalls(),
                "maxInputTokens", reservation.maxInputTokens(),
                "maxOutputTokens", reservation.maxOutputTokens(),
                "maxCostMicros", reservation.maxCostMicros()
        );
    }

    private String sse(String event, Map<String, Object> data) {
        try {
            return "event: " + event + "\n"
                    + "data: " + objectMapper.writeValueAsString(data) + "\n\n";
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize chat stream event.", exception);
        }
    }

    private boolean appendStreamEvents(
            StringBuilder builder,
            StringBuilder sseBuffer,
            String chunk,
            AtomicReference<String> upstreamPhase,
            AtomicBoolean upstreamDone,
            ActiveTurn activeTurn,
            Consumer<StreamEvent> eventConsumer,
            Consumer<String> downstream
    ) {
        boolean receivedDelta = false;
        sseBuffer.append(chunk);
        Matcher separator = SSE_FRAME_SEPARATOR.matcher(sseBuffer);
        while (separator.find()) {
            String eventBlock = sseBuffer.substring(0, separator.start());
            sseBuffer.delete(0, separator.end());
            if (eventBlock.length() > 1_048_576) throw new IllegalStateException("AI stream frame exceeds limit");
            receivedDelta |= appendStreamEvent(builder, eventBlock, upstreamPhase, upstreamDone,
                    activeTurn, eventConsumer, downstream);
            separator = SSE_FRAME_SEPARATOR.matcher(sseBuffer);
        }
        if (sseBuffer.length() > 1_048_576) throw new IllegalStateException("AI stream frame exceeds limit");
        return receivedDelta;
    }

    private boolean appendStreamEvent(
            StringBuilder builder,
            String eventBlock,
            AtomicReference<String> upstreamPhase,
            AtomicBoolean upstreamDone,
            ActiveTurn activeTurn,
            Consumer<StreamEvent> eventConsumer,
            Consumer<String> downstream
    ) {
        if (upstreamDone.get()) return false;
        String currentEvent = "message";
        StringBuilder payload = new StringBuilder();
        boolean heartbeat = false;
        for (String line : eventBlock.split("\\r?\\n")) {
            if (line.startsWith(":")) {
                heartbeat = true;
            } else if (line.startsWith("event:")) {
                currentEvent = line.substring(6).strip();
            } else if (line.startsWith("data:")) {
                if (!payload.isEmpty()) payload.append('\n');
                payload.append(line.substring(5).stripLeading());
            }
        }
        if (payload.isEmpty()) {
            if (heartbeat) downstream.accept(": keep-alive\n\n");
            return false;
        }
        Map<String, Object> data;
        try {
            data = objectMapper.readValue(payload.toString(), new TypeReference<>() {});
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Invalid AI stream event", exception);
        }
        if ("done".equals(currentEvent)) {
            upstreamDone.set(true);
            return false;
        }
        if ("agent.step".equals(currentEvent)) {
            Object sourceTurnId = data.get("turnId");
            if (sourceTurnId != null && !activeTurn.turnId().equals(sourceTurnId)) return false;
            data = chatDebugMetadataSanitizer.progressEvent(data, activeTurn.turnId());
            if (data.isEmpty() || !isNewProgress(activeTurn, data)) return false;
        }
        if ("reasoning.delta".equals(currentEvent)) {
            data = acceptedReasoningEvent(activeTurn, data);
            if (data.isEmpty()) return false;
        }
        if ("tool.call".equals(currentEvent)) {
            data = chatDebugMetadataSanitizer.toolCallEvent(data, activeTurn.turnId());
            if (data.isEmpty() || !isNewToolCall(activeTurn, data)) return false;
        }
        if ("meta".equals(currentEvent)) {
            data.remove("agentProgress");
            data.remove("reasoning");
            data.remove("processStartedAt");
            data.remove("processCompletedAt");
            data.remove("toolCalls");
            data.remove("processTimeline");
        }
        if ("turn.status".equals(currentEvent) && "COMPLETED".equals(data.get("phase"))) {
            // A terminal status without the protocol's done marker is an interrupted response.
            upstreamPhase.set("COMPLETED");
            return false;
        }
        boolean receivedDelta = "delta".equals(currentEvent) && data.get("text") instanceof String text
                && !text.isEmpty();
        if (receivedDelta) builder.append(data.get("text"));
        eventConsumer.accept(new StreamEvent(currentEvent, data));
        if ("turn.status".equals(currentEvent) && data.get("phase") != null) {
            upstreamPhase.set(String.valueOf(data.get("phase")));
        } else if ("error".equals(currentEvent)) {
            upstreamPhase.set("FAILED");
        }
        if ("error".equals(currentEvent)
                || ("turn.status".equals(currentEvent) && "FAILED".equals(data.get("phase")))) {
            synchronized (activeTurn) {
                List<Map<String, Object>> interrupted = interruptRunningToolCalls(activeTurn.metadata(), "任务已中断，未收到工具完成结果。");
                if (!interrupted.isEmpty()) persistActiveTurn(activeTurn);
                interrupted.forEach(call -> downstream.accept(sse("tool.call", call)));
            }
        }
        if ("turn.status".equals(currentEvent)) {
            data.remove("processStartedAt");
            data.remove("processCompletedAt");
            synchronized (activeTurn) {
                for (String key : List.of("processStartedAt", "processCompletedAt")) {
                    if (activeTurn.metadata().containsKey(key)) data.put(key, activeTurn.metadata().get(key));
                }
            }
        }
        // Forward complete frames: lifecycle events and heartbeats can never split upstream JSON.
        downstream.accept(sse(currentEvent, data));
        return receivedDelta;
    }

    private boolean isNewToolCall(ActiveTurn activeTurn, Map<String, Object> data) {
        synchronized (activeTurn) {
            for (Map<String, Object> previous : mapList(activeTurn.metadata().get("toolCalls"))) {
                if (data.get("callId").equals(previous.get("callId"))) {
                    return chatDebugMetadataSanitizer.isNewToolCall(previous, data);
                }
            }
            return true;
        }
    }

    private void appendProcessReference(Map<String, Object> metadata, String type, String id) {
        List<Map<String, Object>> timeline = new ArrayList<>(mapList(metadata.get("processTimeline")));
        timeline.add(Map.of("type", type, "id", id));
        metadata.put("processTimeline", chatDebugMetadataSanitizer.processTimeline(timeline, metadata));
    }

    private void appendReasoningTimeline(Map<String, Object> metadata, Map<String, Object> data) {
        List<Map<String, Object>> timeline = new ArrayList<>(mapList(metadata.get("processTimeline")));
        Map<String, Object> last = timeline.isEmpty() ? Map.of() : timeline.get(timeline.size() - 1);
        Map<String, Object> segment = new LinkedHashMap<>();
        boolean adjacent = "reasoning".equals(last.get("type")) && data.get("kind").equals(last.get("kind"));
        segment.put("type", "reasoning");
        segment.put("id", adjacent ? last.get("id") : "r-" + data.get("sequence"));
        segment.put("text", (adjacent ? stringValue(last.get("text")) : "") + data.get("delta"));
        segment.put("kind", data.get("kind"));
        segment.put("sequence", data.get("sequence"));
        if (adjacent) timeline.set(timeline.size() - 1, segment);
        else timeline.add(segment);
        metadata.put("processTimeline", chatDebugMetadataSanitizer.processTimeline(timeline, metadata));
    }

    private Map<String, Object> acceptedReasoningEvent(ActiveTurn activeTurn, Map<String, Object> raw) {
        Map<String, Object> data = chatDebugMetadataSanitizer.reasoningEvent(raw, activeTurn.turnId());
        if (data.isEmpty()) return data;
        synchronized (activeTurn) {
            Map<String, Object> previous = mapValue(activeTurn.metadata().get("reasoning"));
            if (!previous.isEmpty() && (!data.get("kind").equals(previous.get("kind"))
                    || ((Number) previous.get("sequence")).longValue()
                    >= ((Number) data.get("sequence")).longValue())) return Map.of();
            int remaining = ChatDebugMetadataSanitizer.MAX_REASONING_CHARS
                    - stringValue(previous.get("text")).length();
            if (remaining <= 0) return Map.of();
            String delta = ChatDebugMetadataSanitizer.truncateReasoning((String) data.get("delta"), remaining);
            if (delta.isEmpty()) return Map.of();
            data.put("delta", delta);
            return data;
        }
    }

    private boolean isNewProgress(ActiveTurn activeTurn, Map<String, Object> data) {
        synchronized (activeTurn) {
            if (activeTurn.metadata().get("agentProgress") instanceof List<?> progress) {
                for (Object item : progress) {
                    if (item instanceof Map<?, ?> previous && data.get("step").equals(previous.get("step"))
                            && ((Number) previous.get("sequence")).longValue()
                            >= ((Number) data.get("sequence")).longValue()) return false;
                }
            }
            return true;
        }
    }

    private String streamFailureMessage(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.util.concurrent.TimeoutException
                    || cause.getClass().getSimpleName().contains("Timeout")) {
                return "AI 服务响应超时，已保留生成内容，请稍后重试。";
            }
        }
        return "AI 回复连接中断，已保留生成内容，请稍后重试。";
    }

    private void applyStreamEvent(ActiveTurn activeTurn, StreamEvent event) {
        if (activeTurn.cancelled().get()) {
            throw new ChatTurnCancelledException();
        }
        boolean changed = false;
        synchronized (activeTurn) {
            Map<String, Object> metadata = activeTurn.metadata();
            Map<String, Object> data = event.data();
            switch (event.name()) {
                case "reasoning.delta" -> {
                    Map<String, Object> previous = mapValue(metadata.get("reasoning"));
                    metadata.put("reasoning", Map.of(
                            "text", stringValue(previous.get("text")) + data.get("delta"),
                            "kind", data.get("kind"), "sequence", data.get("sequence")
                    ));
                    appendReasoningTimeline(metadata, data);
                    // Checkpoint reasoning with answer text, never a write per model token.
                }
                case "tool.call" -> {
                    List<Map<String, Object>> calls = new ArrayList<>(mapList(metadata.get("toolCalls")));
                    boolean first = true;
                    for (int index = 0; index < calls.size(); index++) {
                        if (data.get("callId").equals(calls.get(index).get("callId"))) {
                            calls.set(index, data);
                            first = false;
                            break;
                        }
                    }
                    if (first) calls.add(data);
                    if (calls.size() > ChatDebugMetadataSanitizer.MAX_TOOL_CALLS) calls.remove(0);
                    metadata.put("toolCalls", calls);
                    if (first) appendProcessReference(metadata, "tool", stringValue(data.get("callId")));
                    changed = true;
                }
                case "agent.step" -> {
                    List<Map<String, Object>> progress = new ArrayList<>();
                    boolean first = true;
                    if (metadata.get("agentProgress") instanceof List<?> previous) {
                        for (Object item : previous) {
                            if (item instanceof Map<?, ?> step && !data.get("step").equals(step.get("step"))) {
                                progress.add(mapValue(step));
                            } else if (item instanceof Map<?, ?> step && data.get("step").equals(step.get("step"))) {
                                first = false;
                            }
                        }
                    }
                    progress.add(data);
                    progress.sort(java.util.Comparator.comparingLong(item -> ((Number) item.get("sequence")).longValue()));
                    metadata.put("agentProgress", progress);
                    if (first) appendProcessReference(metadata, "agent", stringValue(data.get("step")));
                    changed = true;
                }
                case "turn.status" -> {
                    String phase = stringValue(data.get("phase"));
                    if (data.get("phase") != null) {
                        metadata.put("phase", phase);
                    }
                    if (data.get("message") != null) {
                        metadata.put("statusText", String.valueOf(data.get("message")));
                    }
                    if ("FAILED".equals(phase)) {
                        metadata.putIfAbsent("processCompletedAt", System.currentTimeMillis());
                        failUnfinishedImageItems(metadata, "图片任务已中断");
                        interruptRunningSteps(metadata, "已中断，未收到该步骤的完成结果。");
                    }
                    changed = true;
                }
                case "image.batch.started" -> {
                    metadata.put("imageBatch", imageBatchFromStarted(data));
                    changed = true;
                }
                case "image.item.completed", "image.item.failed" -> {
                    applyImageItem(metadata, data);
                    changed = true;
                }
                case "image.batch.completed" -> {
                    applyImageBatchCompleted(metadata, data);
                    changed = true;
                }
                case "meta" -> {
                    Map<String, Object> rawDebug = new LinkedHashMap<>(metadata);
                    rawDebug.putAll(data);
                    // Final diagnostics cannot overwrite the accepted, ordered live progress.
                    rawDebug.remove("agentProgress");
                    rawDebug.remove("reasoning");
                    rawDebug.remove("processStartedAt");
                    rawDebug.remove("processCompletedAt");
                    rawDebug.remove("toolCalls");
                    rawDebug.remove("processTimeline");
                    // Only ids already validated against the current user may reach durable metadata.
                    rawDebug.put("knowledgeBaseIds", metadata.getOrDefault("knowledgeBaseIds", List.of()));
                    rawDebug.put(
                            "debugTruncated",
                            Boolean.TRUE.equals(metadata.get("debugTruncated"))
                                    || Boolean.TRUE.equals(data.get("debugTruncated"))
                    );
                    metadata.putAll(chatDebugMetadataSanitizer.sanitize(rawDebug));
                    changed = true;
                }
                case "error" -> {
                    metadata.put("phase", "FAILED");
                    metadata.put("statusText", "回复失败");
                    metadata.putIfAbsent("processCompletedAt", System.currentTimeMillis());
                    failUnfinishedImageItems(metadata, stringValue(data.get("message")));
                    interruptRunningSteps(metadata, "已中断，未收到该步骤的完成结果。");
                    changed = true;
                }
                default -> {
                    // Text deltas are checkpointed by the stream loop at a bounded frequency.
                }
            }
            if (changed) {
                persistActiveTurn(activeTurn);
            }
        }
    }

    private Map<String, Object> imageBatchFromStarted(Map<String, Object> data) {
        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("batchId", stringValue(data.get("batchId")));
        batch.put("count", intValue(data.get("count")));
        batch.put("mode", "edit".equals(stringValue(data.get("mode"))) ? "edit" : "generate");
        batch.put("status", "RUNNING");
        batch.put("completedCount", 0);
        batch.put("failedCount", 0);
        List<Map<String, Object>> items = new ArrayList<>();
        if (data.get("items") instanceof List<?> sourceItems) {
            for (Object value : sourceItems) {
                if (value instanceof Map<?, ?> source) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("index", intValue(source.get("index")));
                    item.put("status", stringValue(source.get("status")));
                    items.add(item);
                }
            }
        }
        batch.put("items", items);
        return batch;
    }

    @SuppressWarnings("unchecked")
    private void applyImageItem(Map<String, Object> metadata, Map<String, Object> data) {
        if (!(metadata.get("imageBatch") instanceof Map<?, ?> rawBatch)) {
            return;
        }
        Map<String, Object> batch = (Map<String, Object>) rawBatch;
        if (!stringValue(batch.get("batchId")).equals(stringValue(data.get("batchId")))) {
            return;
        }
        List<Map<String, Object>> items;
        if (batch.get("items") instanceof List<?> rawItems) {
            items = (List<Map<String, Object>>) rawItems;
        } else {
            items = new ArrayList<>();
            batch.put("items", items);
        }
        int targetIndex = intValue(data.get("index"));
        Map<String, Object> target = null;
        for (Map<String, Object> item : items) {
            if (intValue(item.get("index")) == targetIndex) {
                target = item;
                break;
            }
        }
        if (target == null) {
            target = new LinkedHashMap<>();
            target.put("index", targetIndex);
            items.add(target);
        }
        target.put("status", stringValue(data.get("status")));
        copyIfPresent(data, target, "imageUrl");
        copyIfPresent(data, target, "error");
        batch.put("completedCount", intValue(data.get("completedCount")));
        batch.put("failedCount", items.stream()
                .filter(item -> "FAILED".equals(stringValue(item.get("status"))))
                .count());
    }

    @SuppressWarnings("unchecked")
    private void applyImageBatchCompleted(Map<String, Object> metadata, Map<String, Object> data) {
        if (!(metadata.get("imageBatch") instanceof Map<?, ?> rawBatch)) {
            return;
        }
        Map<String, Object> batch = (Map<String, Object>) rawBatch;
        if (!stringValue(batch.get("batchId")).equals(stringValue(data.get("batchId")))) {
            return;
        }
        batch.put("status", stringValue(data.get("status")));
        batch.put("completedCount", intValue(data.get("completedCount")));
        batch.put("failedCount", intValue(data.get("failedCount")));
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.get(key) != null) {
            target.put(key, source.get(key));
        }
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @Override
    public List<ChatMessageVO> listMessages(Long userId, String conversationId) {
        ensureConversationOwnedByUserIfPersisted(userId, conversationId);
        return chatMessageMapper.selectByConversationId(conversationId)
                .stream()
                .map(this::toVO)
                .toList();
    }

    @Override
    public ChatMessageVO cancelTurn(Long userId, String conversationId, String turnId) {
        ensureConversationOwnedByUserIfPersisted(userId, conversationId);
        ActiveTurn activeTurn = activeTurns.get(turnId);
        ChatMessageEntity message;
        if (activeTurn != null && conversationId.equals(activeTurn.conversationId())) {
            synchronized (activeTurn) {
                activeTurn.cancelled().set(true);
                markTurnCancelled(activeTurn.metadata(), activeTurn.message());
                persistActiveTurn(activeTurn);
                message = activeTurn.message();
            }
            activeTurn.workerThread().interrupt();
        } else {
            message = chatMessageMapper.selectByConversationIdAndTurnId(conversationId, turnId);
            if (message == null) {
                throw new ResponseStatusException(NOT_FOUND, "对话轮次不存在");
            }
            Map<String, Object> metadata = readMetadata(message.getMetadataJson());
            markTurnCancelled(metadata, message);
            message.setMetadataJson(writeMetadata(metadata));
            chatMessageMapper.updateById(message);
            touchConversation(conversationId);
        }
        return toVO(message);
    }

    private void ensureConversationOwnedByUserIfPersisted(Long userId, String conversationId) {
        try {
            Long id = Long.parseLong(conversationId);
            if (conversationMapper.selectByIdAndUserId(id, userId) == null) {
                throw new ResponseStatusException(NOT_FOUND, "会话不存在");
            }
        } catch (NumberFormatException ignored) {
            // Temporary UUID conversations are owned by the current request flow.
        }
    }

    private void ensureKnowledgeBasesOwnedByUser(Long userId, List<String> knowledgeBaseIds) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            return;
        }
        if (knowledgeBaseMapper == null) {
            throw new IllegalStateException("Knowledge base ownership validation is unavailable.");
        }
        for (String rawKnowledgeBaseId : knowledgeBaseIds) {
            Long knowledgeBaseId;
            try {
                knowledgeBaseId = Long.valueOf(rawKnowledgeBaseId);
            } catch (NumberFormatException exception) {
                throw new ResponseStatusException(NOT_FOUND, "知识库不存在");
            }
            if (knowledgeBaseId <= 0 || knowledgeBaseMapper.selectByIdAndUserId(knowledgeBaseId, userId) == null) {
                throw new ResponseStatusException(NOT_FOUND, "知识库不存在");
            }
        }
    }

    private ChatMessageEntity saveMessage(String conversationId, String role, String content) {
        return saveMessage(conversationId, null, role, content, Map.of());
    }

    private ChatMessageEntity saveMessage(
            String conversationId,
            String turnId,
            String role,
            String content,
            Map<String, Object> metadata
    ) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setConversationId(conversationId);
        entity.setTurnId(turnId);
        entity.setRole(role);
        entity.setContent(content);
        entity.setMetadataJson(metadata.isEmpty() ? null : writeMetadata(metadata));
        entity.setCreatedAt(LocalDateTime.now());
        chatMessageMapper.insert(entity);
        touchConversation(conversationId);
        return entity;
    }

    private void persistActiveTurn(ActiveTurn activeTurn) {
        activeTurn.message().setMetadataJson(writeMetadata(activeTurn.metadata()));
        chatMessageMapper.updateById(activeTurn.message());
        touchConversation(activeTurn.conversationId());
    }

    @SuppressWarnings("unchecked")
    private void markTurnCancelled(Map<String, Object> metadata, ChatMessageEntity message) {
        metadata.putIfAbsent("processCompletedAt", System.currentTimeMillis());
        metadata.put("phase", "FAILED");
        metadata.put("statusText", "已停止生成");
        interruptRunningSteps(metadata, "已中断：用户停止了本轮生成。");
        interruptRunningToolCalls(metadata, "用户已停止本轮执行。");
        if (metadata.get("imageBatch") instanceof Map<?, ?> rawBatch) {
            Map<String, Object> batch = (Map<String, Object>) rawBatch;
            batch.put("status", "CANCELLED");
            if (batch.get("items") instanceof List<?> rawItems) {
                for (Object value : rawItems) {
                    if (value instanceof Map<?, ?> rawItem) {
                        Map<String, Object> item = (Map<String, Object>) rawItem;
                        String status = stringValue(item.get("status"));
                        if ("QUEUED".equals(status) || "RUNNING".equals(status)) {
                            item.put("status", "CANCELLED");
                        }
                    }
                }
            }
        }
        String content = message.getContent();
        if (StringUtils.isBlank(content)) {
            message.setContent("已停止生成。");
        } else if (!content.contains("已停止生成。")) {
            message.setContent(content + "\n\n已停止生成。");
        }
    }

    private List<Map<String, Object>> interruptRunningSteps(Map<String, Object> metadata, String reason) {
        if (!(metadata.get("agentProgress") instanceof List<?> previous)) return List.of();
        List<Map<String, Object>> normalized = previous.stream()
                .filter(Map.class::isInstance).map(this::mapValue)
                .map(step -> chatDebugMetadataSanitizer.progressEvent(step, stringValue(step.get("turnId"))))
                .filter(step -> !step.isEmpty()).toList();
        long sequence = normalized.stream().mapToLong(step -> ((Number) step.get("sequence")).longValue())
                .max().orElse(0);
        List<Map<String, Object>> progress = new ArrayList<>();
        List<Map<String, Object>> interrupted = new ArrayList<>();
        for (Map<String, Object> step : normalized) {
            if ("running".equals(step.get("status"))) {
                step.put("status", "failed");
                step.put("summary", reason);
                step.put("sequence", ++sequence);
                interrupted.add(step);
            }
            progress.add(step);
        }
        progress.sort(java.util.Comparator.comparingLong(step -> ((Number) step.get("sequence")).longValue()));
        metadata.put("agentProgress", progress);
        return interrupted;
    }

    private List<Map<String, Object>> interruptRunningToolCalls(Map<String, Object> metadata, String reason) {
        if (!(metadata.get("toolCalls") instanceof List<?>)) return List.of();
        List<Map<String, Object>> calls = chatDebugMetadataSanitizer.toolCalls(metadata.get("toolCalls"), metadata.get("turnId"));
        long sequence = calls.stream().mapToLong(call -> ((Number) call.get("sequence")).longValue()).max().orElse(0);
        List<Map<String, Object>> interrupted = new ArrayList<>();
        for (Map<String, Object> call : calls) {
            if (!"running".equals(call.get("status"))) continue;
            call.put("status", "interrupted");
            call.put("summary", reason);
            call.put("sequence", Math.min(++sequence, 9_007_199_254_740_991L));
            interrupted.add(call);
        }
        metadata.put("toolCalls", calls);
        return interrupted;
    }

    @SuppressWarnings("unchecked")
    private void failUnfinishedImageItems(Map<String, Object> metadata, String error) {
        if (!(metadata.get("imageBatch") instanceof Map<?, ?> rawBatch)) {
            return;
        }
        Map<String, Object> batch = (Map<String, Object>) rawBatch;
        String batchStatus = stringValue(batch.get("status"));
        if ("COMPLETED".equals(batchStatus) || "CANCELLED".equals(batchStatus)) {
            return;
        }
        int completedCount = 0;
        int failedCount = 0;
        if (batch.get("items") instanceof List<?> rawItems) {
            for (Object value : rawItems) {
                if (!(value instanceof Map<?, ?> rawItem)) {
                    continue;
                }
                Map<String, Object> item = (Map<String, Object>) rawItem;
                String itemStatus = stringValue(item.get("status"));
                if ("COMPLETED".equals(itemStatus)) {
                    completedCount++;
                    continue;
                }
                if ("QUEUED".equals(itemStatus) || "RUNNING".equals(itemStatus)) {
                    item.put("status", "FAILED");
                    if (!StringUtils.isBlank(error)) {
                        item.put("error", error);
                    }
                    itemStatus = "FAILED";
                }
                if ("FAILED".equals(itemStatus)) {
                    failedCount++;
                }
            }
        }
        batch.put("completedCount", completedCount);
        batch.put("failedCount", failedCount);
        batch.put("status", completedCount > 0 ? "PARTIALLY_COMPLETED" : "FAILED");
    }

    private String writeMetadata(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize chat message metadata.", exception);
        }
    }

    private Map<String, Object> readMetadata(String metadataJson) {
        if (StringUtils.isBlank(metadataJson)) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(metadataJson, new TypeReference<>() {
            });
        } catch (Exception exception) {
            return new LinkedHashMap<>();
        }
    }

    private String historyContent(String content) {
        if (content == null) {
            return "";
        }
        return DATA_IMAGE_PATTERN.matcher(content).replaceAll("[图片 data URL 已省略]");
    }

    private ChatResponseVO existingResponse(String conversationId, ChatMessageEntity existing) {
        Map<String, Object> metadata = readMetadata(existing.getMetadataJson());
        Map<String, Object> safeDebug = chatDebugMetadataSanitizer.sanitize(metadata);
        return new ChatResponseVO(
                conversationId,
                existing.getContent(),
                sourceList(safeDebug.get("sources")),
                mapList(safeDebug.get("agentTrace")),
                mapList(safeDebug.get("toolResults")),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(),
                mapValue(safeDebug.get("review")),
                usageFromMetadata(safeDebug),
                mapList(safeDebug.get("usedMemories")),
                mapValue(safeDebug.get("memoryExclusions")),
                stringValue(safeDebug.getOrDefault("memoryExplanationVersion", "v1")),
                stringValue(safeDebug.get("debugVersion")),
                stringList(safeDebug.get("knowledgeBaseIds")),
                mapValue(safeDebug.get("retrievalScope")),
                Boolean.TRUE.equals(safeDebug.get("debugTruncated"))
        );
    }

    private Map<String, Object> replayMetadata(Map<String, Object> metadata) {
        return chatDebugMetadataSanitizer.sanitize(metadata);
    }

    private Map<String, Object> debugPayload(AiChatResponseDTO response, List<String> knowledgeBaseIds) {
        Map<String, Object> raw = new LinkedHashMap<>();
        if (ChatDebugMetadataSanitizer.DEBUG_VERSION.equals(response.debugVersion())) {
            raw.put("debugVersion", ChatDebugMetadataSanitizer.DEBUG_VERSION);
        }
        raw.put("knowledgeBaseIds", knowledgeBaseIds == null ? List.of() : knowledgeBaseIds);
        raw.put("retrievalScope", response.retrievalScope() == null ? Map.of() : response.retrievalScope());
        raw.put("sources", response.sources() == null ? List.of() : response.sources());
        raw.put("agentTrace", response.agentTrace() == null ? List.of() : response.agentTrace());
        raw.put("toolResults", response.toolResults() == null ? List.of() : response.toolResults());
        raw.put("review", response.review() == null ? Map.of() : response.review());
        raw.put("usage", response.usage());
        raw.put("usedMemories", response.usedMemories() == null ? List.of() : response.usedMemories());
        raw.put("memoryExclusions", response.memoryExclusions() == null ? Map.of() : response.memoryExclusions());
        raw.put(
                "memoryExplanationVersion",
                StringUtils.isBlank(response.memoryExplanationVersion()) ? "v1" : response.memoryExplanationVersion()
        );
        raw.put("debugTruncated", Boolean.TRUE.equals(response.debugTruncated()));
        return raw;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) new LinkedHashMap<>((Map<String, Object>) item))
                .toList();
    }

    private List<SourceVO> sourceList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<SourceVO> sources = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> source)) continue;
            sources.add(new SourceVO(
                    stringValue(source.get("docId")),
                    stringValue(source.get("title")),
                    stringValue(source.get("chunk")),
                    stringValue(source.get("score")),
                    stringValue(source.get("backend")),
                    stringValue(source.get("hitModes")),
                    stringValue(source.get("chunkIndex")),
                    stringValue(source.get("parentTitle")),
                    stringValue(source.get("startChar")),
                    stringValue(source.get("endChar"))
            ));
        }
        return List.copyOf(sources);
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(item -> item != null)
                .map(String::valueOf)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Object>) map)
                : Map.of();
    }

    private com.example.aiapp.domain.dto.UsageDTO usageFromMetadata(Map<String, Object> metadata) {
        Map<String, Object> usage = mapValue(metadata.get("usage"));
        return new com.example.aiapp.domain.dto.UsageDTO(
                intValue(usage.get("promptTokens")),
                intValue(usage.get("completionTokens")),
                intValue(usage.get("callCount")),
                longValue(usage.get("estimatedCostMicros")),
                stringValue(usage.getOrDefault("measurementSource", "PENDING"))
        );
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    private String normalizedMessage(String message, List<String> attachmentIds) {
        if (!StringUtils.isBlank(message)) return message.trim();
        if (attachmentIds != null && !attachmentIds.isEmpty()) return "请分析这些附件";
        throw new ResponseStatusException(BAD_REQUEST, "消息或附件不能为空");
    }

    private String withRecentImageContext(String conversationId, String message) {
        if (StringUtils.isBlank(message) || containsImageRef(message) || !looksLikeImageFollowUp(message)) {
            return message;
        }
        List<String> imageUrls = findReferencedImageUrls(conversationId, message);
        if (imageUrls.isEmpty()) {
            return message;
        }
        StringBuilder enriched = new StringBuilder(message);
        for (int index = 0; index < imageUrls.size(); index++) {
            enriched.append("\n图片").append(index + 1).append("：").append(imageUrls.get(index));
        }
        return enriched.toString();
    }

    private String withRecentImageContextUnlessCurrentImage(
            String conversationId,
            String message,
            List<AiChatAttachmentDTO> attachments
    ) {
        boolean hasCurrentImage = attachments != null && attachments.stream()
                .anyMatch(attachment -> attachment != null && "image".equalsIgnoreCase(attachment.kind()));
        return hasCurrentImage ? message : withRecentImageContext(conversationId, message);
    }

    private boolean looksLikeImageFollowUp(String message) {
        return List.of(
                "这张图",
                "这些图",
                "这几张",
                "上一张",
                "图片",
                "照片",
                "图里",
                "改图",
                "重绘",
                "修改",
                "改一下",
                "换背景",
                "去掉",
                "移除",
                "替换",
                "参考",
                "结合"
        ).stream().anyMatch(message::contains);
    }

    private boolean containsImageRef(String message) {
        return message.contains("data:image/") || IMAGE_URL_PATTERN.matcher(message).find();
    }

    private List<String> findReferencedImageUrls(String conversationId, String message) {
        List<ImageRef> images = findRecentImageRefs(conversationId);
        if (images.isEmpty()) {
            return List.of();
        }
        if (message.contains("这些图")
                || message.contains("这几张")
                || message.contains("两张")
                || message.contains("多张")
                || message.contains("一起参考")
                || message.contains("结合")) {
            int requested = requestedImageCount(message, images.size());
            return images.stream().limit(Math.min(requested, images.size())).map(ImageRef::url).toList();
        }
        ImageRef selected = selectImageRef(images, message);
        return selected == null ? List.of() : List.of(selected.url());
    }

    private int requestedImageCount(String message, int available) {
        Matcher count = Pattern.compile("([1-8一二两三四五六七八])\\s*张").matcher(message);
        if (count.find()) {
            String value = count.group(1);
            if (Character.isDigit(value.charAt(0))) {
                return Integer.parseInt(value);
            }
            return switch (value) {
                case "一" -> 1;
                case "二", "两" -> 2;
                case "三" -> 3;
                case "四" -> 4;
                case "五" -> 5;
                case "六" -> 6;
                case "七" -> 7;
                case "八" -> 8;
                default -> Math.min(8, available);
            };
        }
        return Math.min(8, available);
    }

    private ImageRef selectImageRef(List<ImageRef> images, String message) {
        if (images.size() == 1) {
            return images.get(0);
        }
        if (message.contains("第一张") || message.contains("最早") || message.contains("那张卡通") || message.contains("卡通图")) {
            return images.get(images.size() - 1);
        }
        if (message.contains("第二张") && images.size() >= 2) {
            return images.get(images.size() - 2);
        }
        if (message.contains("上一张") || message.contains("上张")) {
            ImageRef nonLatest = firstMatchingFrom(1, images, message);
            return nonLatest == null ? images.get(Math.min(1, images.size() - 1)) : nonLatest;
        }
        ImageRef semantic = firstMatchingFrom(0, images, message);
        return semantic == null ? images.get(0) : semantic;
    }

    private ImageRef firstMatchingFrom(int startIndex, List<ImageRef> images, String message) {
        for (int index = startIndex; index < images.size(); index++) {
            ImageRef image = images.get(index);
            String content = image.context();
            if (message.contains("卡通") && containsAny(content, "卡通", "动画", "小孩", "写代码", "电脑")) {
                return image;
            }
            if ((message.contains("人像") || message.contains("真人") || message.contains("女生"))
                    && containsAny(content, "人像", "真人", "女生", "照片", "公园")) {
                return image;
            }
        }
        return null;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private List<ImageRef> findRecentImageRefs(String conversationId) {
        List<ChatMessageEntity> messages = chatMessageMapper.selectByConversationId(conversationId);
        List<ImageRef> images = new ArrayList<>();
        for (int index = messages.size() - 1; index >= 0; index--) {
            String content = messages.get(index).getContent();
            if (StringUtils.isBlank(content)) {
                continue;
            }
            Matcher matcher = IMAGE_URL_PATTERN.matcher(content);
            List<String> urls = new ArrayList<>();
            while (matcher.find()) {
                urls.add(matcher.group(0).replaceAll("[，。,.!！)）]+$", ""));
            }
            Collections.reverse(urls);
            for (String url : urls) {
                images.add(new ImageRef(url, content));
                if (images.size() >= 8) {
                    return images;
                }
            }
        }
        return images;
    }

    private record ImageRef(String url, String context) {
    }

    private void touchConversation(String conversationId) {
        try {
            ConversationEntity entity = new ConversationEntity();
            entity.setId(Long.parseLong(conversationId));
            entity.setUpdatedAt(LocalDateTime.now());
            conversationMapper.updateById(entity);
        } catch (NumberFormatException ignored) {
            // Some temporary conversation ids may not come from the conversation table.
        }
    }

    private ChatMessageVO toVO(ChatMessageEntity entity) {
        Map<String, Object> metadata = readMetadata(entity.getMetadataJson());
        if ("assistant".equals(entity.getRole())) {
            Map<String, Object> safeMetadata = new LinkedHashMap<>();
            copyHistoryMetadata(metadata, safeMetadata, "turnId");
            copyHistoryMetadata(metadata, safeMetadata, "phase");
            copyHistoryMetadata(metadata, safeMetadata, "statusText");
            if (metadata.get("imageBatch") instanceof Map<?, ?> imageBatch) {
                // Image batch metadata is produced and bounded by this service,
                // and remains outside the agent-debug envelope.
                safeMetadata.put("imageBatch", new LinkedHashMap<>(imageBatch));
            }
            safeMetadata.putAll(chatDebugMetadataSanitizer.sanitize(metadata));
            metadata = safeMetadata;
        }
        return new ChatMessageVO(
                entity.getId(),
                entity.getConversationId(),
                entity.getTurnId(),
                entity.getRole(),
                entity.getContent(),
                metadata,
                entity.getCreatedAt(),
                chatAttachmentService == null ? List.of() : chatAttachmentService.listForMessage(entity.getId())
        );
    }

    private void copyHistoryMetadata(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.get(key) != null) {
            target.put(key, source.get(key));
        }
    }

    private record StreamEvent(String name, Map<String, Object> data) {
    }

    private record ActiveTurn(
            String conversationId,
            String turnId,
            ChatMessageEntity message,
            Map<String, Object> metadata,
            Thread workerThread,
            AtomicBoolean cancelled
    ) {
        private ActiveTurn(
                String conversationId,
                String turnId,
                ChatMessageEntity message,
                Map<String, Object> metadata,
                Thread workerThread
        ) {
            this(conversationId, turnId, message, metadata, workerThread, new AtomicBoolean(false));
        }
    }

    private static final class ChatTurnCancelledException extends RuntimeException {
    }
}
