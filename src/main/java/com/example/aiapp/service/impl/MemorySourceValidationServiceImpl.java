package com.example.aiapp.service.impl;

import com.example.aiapp.domain.entity.ChatMessageEntity;
import com.example.aiapp.domain.entity.ConversationEntity;
import com.example.aiapp.domain.entity.KnowledgeBaseEntity;
import com.example.aiapp.domain.entity.KnowledgeFileEntity;
import com.example.aiapp.mapper.ChatMessageMapper;
import com.example.aiapp.mapper.ConversationMapper;
import com.example.aiapp.mapper.KnowledgeBaseMapper;
import com.example.aiapp.mapper.KnowledgeFileMapper;
import com.example.aiapp.service.MemorySourceValidationService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 记忆来源校验服务实现。
 */
@Service
public class MemorySourceValidationServiceImpl implements MemorySourceValidationService {

    private final ConversationMapper conversationMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeFileMapper knowledgeFileMapper;

    public MemorySourceValidationServiceImpl(
            ConversationMapper conversationMapper,
            ChatMessageMapper chatMessageMapper,
            KnowledgeBaseMapper knowledgeBaseMapper,
            KnowledgeFileMapper knowledgeFileMapper
    ) {
        this.conversationMapper = conversationMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.knowledgeFileMapper = knowledgeFileMapper;
    }

    @Override
    public List<Map<String, Object>> validate(String rawUserId, List<Map<String, Object>> sources) {
        Long userId = positiveLong(rawUserId);
        return (sources == null ? List.<Map<String, Object>>of() : sources).stream()
                .map(source -> validateOne(userId, source))
                .toList();
    }

    private Map<String, Object> validateOne(Long userId, Map<String, Object> source) {
        String id = text(source == null ? null : source.get("id"));
        String type = text(source == null ? null : source.get("type")).toUpperCase(Locale.ROOT);
        boolean available = false;
        String reason = "原始内容已删除或当前用户无权访问";
        if (userId != null && "CONVERSATION".equals(type)) {
            available = conversationSourceExists(
                    userId,
                    positiveLong(source.get("conversationId")),
                    positiveLong(source.get("messageId"))
            );
            reason = "原会话或消息已删除，或当前用户无权访问";
        } else if (userId != null && "KNOWLEDGE".equals(type)) {
            available = knowledgeSourceExists(userId, positiveLong(source.get("sourceId")));
            reason = "原知识文件已删除，或当前用户无权访问";
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("available", available);
        result.put("reason", available ? null : reason);
        return result;
    }

    private boolean conversationSourceExists(Long userId, Long conversationId, Long messageId) {
        if (conversationId == null || messageId == null) return false;
        ConversationEntity conversation = conversationMapper.selectByIdAndUserId(conversationId, userId);
        if (conversation == null) return false;
        ChatMessageEntity message = chatMessageMapper.selectById(messageId);
        return message != null && String.valueOf(conversationId).equals(message.getConversationId());
    }

    private boolean knowledgeSourceExists(Long userId, Long fileId) {
        if (fileId == null) return false;
        KnowledgeFileEntity file = knowledgeFileMapper.selectById(fileId);
        if (file == null || file.getKnowledgeBaseId() == null) return false;
        KnowledgeBaseEntity knowledgeBase = knowledgeBaseMapper.selectByIdAndUserId(file.getKnowledgeBaseId(), userId);
        return knowledgeBase != null;
    }

    private static Long positiveLong(Object value) {
        try {
            long parsed = Long.parseLong(text(value));
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
