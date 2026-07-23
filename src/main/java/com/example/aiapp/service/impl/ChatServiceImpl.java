package com.example.aiapp.service.impl;

import com.example.aiapp.domain.dto.AiChatRequestDTO;
import com.example.aiapp.domain.dto.AiChatResponseDTO;
import com.example.aiapp.domain.dto.AiChatAttachmentDTO;
import com.example.aiapp.domain.dto.ChatRequestDTO;
import com.example.aiapp.domain.entity.ChatMessageEntity;
import com.example.aiapp.domain.entity.ConversationEntity;
import com.example.aiapp.domain.vo.ChatMessageVO;
import com.example.aiapp.domain.vo.ChatResponseVO;
import com.example.aiapp.infrastructure.client.AiServiceClient;
import com.example.aiapp.mapper.ChatMessageMapper;
import com.example.aiapp.mapper.ConversationMapper;
import com.example.aiapp.service.AiConfigService;
import com.example.aiapp.service.ChatAttachmentService;
import com.example.aiapp.service.ChatService;
import com.example.aiapp.utils.StringUtils;
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

@Service
public class ChatServiceImpl implements ChatService {

    private static final java.util.regex.Pattern DATA_IMAGE_PATTERN = java.util.regex.Pattern.compile(
            "data:image/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=\\r\\n]+"
    );
    private static final Pattern IMAGE_URL_PATTERN = Pattern.compile(
            "https?://\\S+\\.(?:png|jpe?g|webp|gif)(?:\\?\\S*)?",
            Pattern.CASE_INSENSITIVE
    );

    private final AiServiceClient aiServiceClient;
    private final AiConfigService aiConfigService;
    private final ChatMessageMapper chatMessageMapper;
    private final ConversationMapper conversationMapper;
    private final ObjectMapper objectMapper;
    private ChatAttachmentService chatAttachmentService;
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
    }

    @Autowired
    void setChatAttachmentService(ChatAttachmentService chatAttachmentService) {
        this.chatAttachmentService = chatAttachmentService;
    }

    @Override
    public ChatResponseVO send(Long userId, ChatRequestDTO request) {
        String conversationId = StringUtils.isBlank(request.conversationId())
                ? UUID.randomUUID().toString()
                : request.conversationId();
        ensureConversationOwnedByUserIfPersisted(userId, conversationId);
        if (!StringUtils.isBlank(request.turnId())) {
            ChatMessageEntity existing = chatMessageMapper.selectByConversationIdAndTurnId(conversationId, request.turnId());
            if (existing != null) {
                return existingResponse(conversationId, existing);
            }
        }

        String userMessage = normalizedMessage(request.message(), request.attachmentIds());
        var attachments = chatAttachmentService == null
                ? List.<com.example.aiapp.domain.dto.AiChatAttachmentDTO>of()
                : chatAttachmentService.requireReady(userId, request.attachmentIds());

        String aiMessage = withRecentImageContextUnlessCurrentImage(conversationId, userMessage, attachments);
        AiChatRequestDTO aiRequest = new AiChatRequestDTO(
                String.valueOf(userId),
                conversationId,
                request.turnId(),
                request.projectId(),
                aiMessage,
                request.knowledgeBaseIds() == null ? List.of() : request.knowledgeBaseIds(),
                false,
                aiConfigService.currentConfigMap(),
                attachments
        );

        ChatMessageEntity userEntity = saveMessage(conversationId, request.turnId(), "user", historyContent(userMessage), Map.of());
        if (chatAttachmentService != null) {
            chatAttachmentService.bind(userId, conversationId, userEntity.getId(), request.attachmentIds());
        }
        AiChatResponseDTO aiResponse = aiServiceClient.chat(aiRequest);
        saveMessage(conversationId, "assistant", aiResponse.answer());
        return new ChatResponseVO(
                conversationId,
                aiResponse.answer(),
                aiResponse.sources() == null ? List.of() : aiResponse.sources(),
                aiResponse.agentTrace() == null ? List.of() : aiResponse.agentTrace(),
                aiResponse.toolResults() == null ? List.of() : aiResponse.toolResults(),
                aiResponse.candidateMemories() == null ? List.of() : aiResponse.candidateMemories(),
                aiResponse.candidateTasks() == null ? List.of() : aiResponse.candidateTasks(),
                aiResponse.candidateGoals() == null ? List.of() : aiResponse.candidateGoals(),
                aiResponse.candidateDecisions() == null ? List.of() : aiResponse.candidateDecisions(),
                aiResponse.candidateOpenQuestions() == null ? List.of() : aiResponse.candidateOpenQuestions(),
                aiResponse.candidatePlans() == null ? List.of() : aiResponse.candidatePlans(),
                aiResponse.executionSuggestions() == null ? List.of() : aiResponse.executionSuggestions(),
                aiResponse.projectContext() == null ? java.util.Map.of() : aiResponse.projectContext(),
                aiResponse.review() == null ? java.util.Map.of() : aiResponse.review()
        );
    }

    @Override
    public void stream(Long userId, ChatRequestDTO request, Consumer<String> chunkConsumer) {
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
        AiChatRequestDTO aiRequest = new AiChatRequestDTO(
                String.valueOf(userId),
                conversationId,
                turnId,
                request.projectId(),
                aiMessage,
                request.knowledgeBaseIds() == null ? List.of() : request.knowledgeBaseIds(),
                true,
                aiConfigService.currentConfigMap(),
                attachments
        );

        if (!retrying) {
            ChatMessageEntity userEntity = saveMessage(conversationId, turnId, "user", historyContent(userMessage), Map.of());
            if (chatAttachmentService != null) {
                chatAttachmentService.bind(userId, conversationId, userEntity.getId(), request.attachmentIds());
            }
        }
        StringBuilder assistantAnswer = new StringBuilder();
        StringBuilder sseBuffer = new StringBuilder();
        AtomicReference<String> upstreamPhase = new AtomicReference<>();
        Map<String, Object> initialMetadata = new LinkedHashMap<>();
        initialMetadata.put("turnId", turnId);
        initialMetadata.put("phase", "THINKING");
        initialMetadata.put("statusText", "正在理解你的需求…");
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
                "message", "正在理解你的需求…"
        )));
        AtomicBoolean responding = new AtomicBoolean();
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
                                event -> applyStreamEvent(activeTurn, event)
                        );
                        // Forward the current upstream bytes before publishing a
                        // derived lifecycle frame. Otherwise a DataBuffer boundary
                        // inside JSON can cause the lifecycle frame to be injected
                        // into the middle of that SSE event.
                        chunkConsumer.accept(chunk);
                        String phase = upstreamPhase.get();
                        if (receivedDelta
                                && responding.compareAndSet(false, true)
                                && (phase == null || "THINKING".equals(phase))) {
                            chunkConsumer.accept(sse("turn.status", Map.of(
                                    "turnId", turnId,
                                    "phase", "RESPONDING",
                                    "message", "正在回复…"
                            )));
                        }
                    })
                    .blockLast();
            if (activeTurn.cancelled().get()) {
                return;
            }
            String finalUpstreamPhase = upstreamPhase.get();
            if (!"COMPLETED".equals(finalUpstreamPhase) && !"FAILED".equals(finalUpstreamPhase)) {
                chunkConsumer.accept(sse("turn.status", Map.of(
                        "turnId", turnId,
                        "phase", "COMPLETED",
                        "message", "回复完成"
                )));
                synchronized (activeTurn) {
                    activeTurn.metadata().put("phase", "COMPLETED");
                    activeTurn.metadata().put("statusText", "回复完成");
                }
            }
            synchronized (activeTurn) {
                activeTurn.message().setContent(assistantAnswer.toString());
                persistActiveTurn(activeTurn);
            }
        } catch (RuntimeException exception) {
            if (activeTurn.cancelled().get() || exception instanceof ChatTurnCancelledException) {
                return;
            }
            synchronized (activeTurn) {
                activeTurn.metadata().put("phase", "FAILED");
                activeTurn.metadata().put("statusText", "回复失败");
                failUnfinishedImageItems(activeTurn.metadata(), "图片任务已中断");
                activeTurn.message().setContent(
                        assistantAnswer.isEmpty() ? "回复失败，请稍后重试。" : assistantAnswer.toString()
                );
                persistActiveTurn(activeTurn);
            }
            chunkConsumer.accept(sse("turn.status", Map.of(
                    "turnId", turnId,
                    "phase", "FAILED",
                    "message", "回复失败"
            )));
            throw exception;
        } finally {
            activeTurns.remove(turnId, activeTurn);
        }
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
            Consumer<StreamEvent> eventConsumer
    ) {
        boolean receivedDelta = false;
        sseBuffer.append(chunk);
        int eventEnd;
        while ((eventEnd = sseBuffer.indexOf("\n\n")) >= 0) {
            String eventBlock = sseBuffer.substring(0, eventEnd);
            sseBuffer.delete(0, eventEnd + 2);
            receivedDelta |= appendStreamEvent(builder, eventBlock, upstreamPhase, eventConsumer);
        }
        return receivedDelta;
    }

    private boolean appendStreamEvent(
            StringBuilder builder,
            String eventBlock,
            AtomicReference<String> upstreamPhase,
            Consumer<StreamEvent> eventConsumer
    ) {
        String currentEvent = "";
        for (String line : eventBlock.split("\\n")) {
            if (line.startsWith("event: ")) {
                currentEvent = line.substring("event: ".length()).trim();
                continue;
            }
            if (!line.startsWith("data: ")) {
                continue;
            }
            try {
                Map<String, Object> data = objectMapper.readValue(
                        line.substring("data: ".length()),
                        new TypeReference<>() {
                        }
                );
                eventConsumer.accept(new StreamEvent(currentEvent, data));
                if ("delta".equals(currentEvent)) {
                    Object text = data.get("text");
                    if (text != null) {
                        builder.append(text);
                    }
                    return true;
                }
                if ("turn.status".equals(currentEvent) && data.get("phase") != null) {
                    upstreamPhase.set(String.valueOf(data.get("phase")));
                } else if ("error".equals(currentEvent)) {
                    upstreamPhase.set("FAILED");
                }
            } catch (Exception ignored) {
                // Streaming chunks are still forwarded to the client even if local history extraction misses one.
            }
        }
        return false;
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
                case "turn.status" -> {
                    String phase = stringValue(data.get("phase"));
                    if (data.get("phase") != null) {
                        metadata.put("phase", phase);
                    }
                    if (data.get("message") != null) {
                        metadata.put("statusText", String.valueOf(data.get("message")));
                    }
                    if ("FAILED".equals(phase)) {
                        failUnfinishedImageItems(metadata, "图片任务已中断");
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
                case "error" -> {
                    metadata.put("phase", "FAILED");
                    metadata.put("statusText", "回复失败");
                    failUnfinishedImageItems(metadata, stringValue(data.get("message")));
                    changed = true;
                }
                default -> {
                    // Text deltas are accumulated and persisted once the turn reaches a terminal state.
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
        metadata.put("phase", "FAILED");
        metadata.put("statusText", "已停止生成");
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
        return new ChatResponseVO(
                conversationId, existing.getContent(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of()
        );
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
        return new ChatMessageVO(
                entity.getId(),
                entity.getConversationId(),
                entity.getTurnId(),
                entity.getRole(),
                entity.getContent(),
                readMetadata(entity.getMetadataJson()),
                entity.getCreatedAt(),
                chatAttachmentService == null ? List.of() : chatAttachmentService.listForMessage(entity.getId())
        );
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
