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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.springframework.http.HttpStatus.NOT_FOUND;

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

        String aiMessage = withRecentImageContext(conversationId, request.message());
        AiChatRequestDTO aiRequest = new AiChatRequestDTO(
                String.valueOf(userId),
                conversationId,
                request.projectId(),
                aiMessage,
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
        ensureConversationOwnedByUserIfPersisted(userId, conversationId);

        String aiMessage = withRecentImageContext(conversationId, request.message());
        AiChatRequestDTO aiRequest = new AiChatRequestDTO(
                String.valueOf(userId),
                conversationId,
                request.projectId(),
                aiMessage,
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

    private String withRecentImageContext(String conversationId, String message) {
        if (StringUtils.isBlank(message) || containsImageRef(message) || !looksLikeImageFollowUp(message)) {
            return message;
        }
        String imageUrl = findReferencedImageUrl(conversationId, message);
        if (StringUtils.isBlank(imageUrl)) {
            return message;
        }
        return message + "\n图片：" + imageUrl;
    }

    private boolean looksLikeImageFollowUp(String message) {
        return List.of(
                "这张图",
                "图片",
                "照片",
                "图里",
                "改图",
                "修改",
                "改一下",
                "换背景",
                "去掉",
                "移除",
                "替换"
        ).stream().anyMatch(message::contains);
    }

    private boolean containsImageRef(String message) {
        return message.contains("data:image/") || IMAGE_URL_PATTERN.matcher(message).find();
    }

    private String findReferencedImageUrl(String conversationId, String message) {
        List<ImageRef> images = findRecentImageRefs(conversationId);
        if (images.isEmpty()) {
            return "";
        }
        ImageRef selected = selectImageRef(images, message);
        return selected == null ? "" : selected.url();
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
                entity.getRole(),
                entity.getContent(),
                entity.getCreatedAt()
        );
    }
}
