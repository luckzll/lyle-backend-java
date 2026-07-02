package com.example.aiapp.service.impl;

import com.example.aiapp.domain.dto.AiChatRequestDTO;
import com.example.aiapp.domain.dto.AiChatResponseDTO;
import com.example.aiapp.domain.dto.ChatRequestDTO;
import com.example.aiapp.domain.entity.ChatMessageEntity;
import com.example.aiapp.domain.entity.ConversationEntity;
import com.example.aiapp.domain.vo.ChatMessageVO;
import com.example.aiapp.domain.vo.ChatResponseVO;
import com.example.aiapp.infrastructure.client.AiServiceClient;
import com.example.aiapp.mapper.ChatMessageMapper;
import com.example.aiapp.mapper.ConversationMapper;
import com.example.aiapp.service.AiConfigService;
import com.example.aiapp.service.ChatService;
import com.example.aiapp.utils.StringUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class ChatServiceImpl implements ChatService {

    private static final java.util.regex.Pattern DATA_IMAGE_PATTERN = java.util.regex.Pattern.compile(
            "data:image/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=\\r\\n]+"
    );

    private final AiServiceClient aiServiceClient;
    private final AiConfigService aiConfigService;
    private final ChatMessageMapper chatMessageMapper;
    private final ConversationMapper conversationMapper;
    private final ObjectMapper objectMapper;

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

    @Override
    public ChatResponseVO send(Long userId, ChatRequestDTO request) {
        String conversationId = StringUtils.isBlank(request.conversationId())
                ? UUID.randomUUID().toString()
                : request.conversationId();
        ensureConversationOwnedByUserIfPersisted(userId, conversationId);

        AiChatRequestDTO aiRequest = new AiChatRequestDTO(
                String.valueOf(userId),
                conversationId,
                request.message(),
                request.knowledgeBaseIds() == null ? List.of() : request.knowledgeBaseIds(),
                false,
                aiConfigService.currentConfigMap()
        );

        saveMessage(conversationId, "user", historyContent(request.message()));
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
                aiResponse.review() == null ? java.util.Map.of() : aiResponse.review()
        );
    }

    @Override
    public void stream(Long userId, ChatRequestDTO request, Consumer<String> chunkConsumer) {
        String conversationId = StringUtils.isBlank(request.conversationId())
                ? UUID.randomUUID().toString()
                : request.conversationId();
        ensureConversationOwnedByUserIfPersisted(userId, conversationId);

        AiChatRequestDTO aiRequest = new AiChatRequestDTO(
                String.valueOf(userId),
                conversationId,
                request.message(),
                request.knowledgeBaseIds() == null ? List.of() : request.knowledgeBaseIds(),
                true,
                aiConfigService.currentConfigMap()
        );

        saveMessage(conversationId, "user", historyContent(request.message()));
        StringBuilder assistantAnswer = new StringBuilder();
        StringBuilder sseBuffer = new StringBuilder();
        aiServiceClient.streamChat(aiRequest)
                .doOnNext(chunk -> {
                    appendDeltaText(assistantAnswer, sseBuffer, chunk);
                    chunkConsumer.accept(chunk);
                })
                .blockLast();
        saveMessage(conversationId, "assistant", assistantAnswer.toString());
    }

    private void appendDeltaText(StringBuilder builder, StringBuilder sseBuffer, String chunk) {
        sseBuffer.append(chunk);
        int eventEnd;
        while ((eventEnd = sseBuffer.indexOf("\n\n")) >= 0) {
            String eventBlock = sseBuffer.substring(0, eventEnd);
            sseBuffer.delete(0, eventEnd + 2);
            appendDeltaEvent(builder, eventBlock);
        }
    }

    private void appendDeltaEvent(StringBuilder builder, String eventBlock) {
        String currentEvent = "";
        for (String line : eventBlock.split("\\n")) {
            if (line.startsWith("event: ")) {
                currentEvent = line.substring("event: ".length()).trim();
                continue;
            }
            if (!"delta".equals(currentEvent) || !line.startsWith("data: ")) {
                continue;
            }
            try {
                Map<String, Object> data = objectMapper.readValue(
                        line.substring("data: ".length()),
                        new TypeReference<>() {
                        }
                );
                Object text = data.get("text");
                if (text != null) {
                    builder.append(text);
                }
            } catch (Exception ignored) {
                // Streaming chunks are still forwarded to the client even if local history extraction misses one.
            }
        }
    }

    @Override
    public List<ChatMessageVO> listMessages(Long userId, String conversationId) {
        ensureConversationOwnedByUserIfPersisted(userId, conversationId);
        return chatMessageMapper.selectByConversationId(conversationId)
                .stream()
                .map(this::toVO)
                .toList();
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

    private void saveMessage(String conversationId, String role, String content) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setConversationId(conversationId);
        entity.setRole(role);
        entity.setContent(content);
        entity.setCreatedAt(LocalDateTime.now());
        chatMessageMapper.insert(entity);
        touchConversation(conversationId);
    }

    private String historyContent(String content) {
        if (content == null) {
            return "";
        }
        return DATA_IMAGE_PATTERN.matcher(content).replaceAll("[图片 data URL 已省略]");
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
                entity.getRole(),
                entity.getContent(),
                entity.getCreatedAt()
        );
    }
}
