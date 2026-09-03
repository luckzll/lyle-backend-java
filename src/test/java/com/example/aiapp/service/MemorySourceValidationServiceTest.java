package com.example.aiapp.service;

import com.example.aiapp.domain.entity.ChatMessageEntity;
import com.example.aiapp.domain.entity.ConversationEntity;
import com.example.aiapp.domain.entity.KnowledgeBaseEntity;
import com.example.aiapp.domain.entity.KnowledgeFileEntity;
import com.example.aiapp.mapper.ChatMessageMapper;
import com.example.aiapp.mapper.ConversationMapper;
import com.example.aiapp.mapper.KnowledgeBaseMapper;
import com.example.aiapp.mapper.KnowledgeFileMapper;
import com.example.aiapp.service.impl.MemorySourceValidationServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemorySourceValidationServiceTest {

    @Test
    void validatesConversationMessageAndKnowledgeOwnership() {
        ConversationMapper conversationMapper = mock(ConversationMapper.class);
        ChatMessageMapper chatMessageMapper = mock(ChatMessageMapper.class);
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        KnowledgeFileMapper knowledgeFileMapper = mock(KnowledgeFileMapper.class);
        MemorySourceValidationService service = new MemorySourceValidationServiceImpl(
                conversationMapper, chatMessageMapper, knowledgeBaseMapper, knowledgeFileMapper
        );

        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(66L);
        conversation.setUserId(42L);
        ChatMessageEntity message = new ChatMessageEntity();
        message.setId(8801L);
        message.setConversationId("66");
        KnowledgeFileEntity file = new KnowledgeFileEntity();
        file.setId(300L);
        file.setKnowledgeBaseId(200L);
        KnowledgeBaseEntity knowledgeBase = new KnowledgeBaseEntity();
        knowledgeBase.setId(200L);
        knowledgeBase.setUserId(42L);

        when(conversationMapper.selectByIdAndUserId(66L, 42L)).thenReturn(conversation);
        when(chatMessageMapper.selectById(8801L)).thenReturn(message);
        when(knowledgeFileMapper.selectById(300L)).thenReturn(file);
        when(knowledgeBaseMapper.selectByIdAndUserId(200L, 42L)).thenReturn(knowledgeBase);

        List<Map<String, Object>> result = service.validate("42", List.of(
                Map.of("id", "conversation-ok", "type", "CONVERSATION", "conversationId", "66", "messageId", "8801"),
                Map.of("id", "conversation-missing", "type", "CONVERSATION", "conversationId", "66", "messageId", "9999"),
                Map.of("id", "knowledge-ok", "type", "KNOWLEDGE", "sourceId", "300")
        ));

        assertThat(result).extracting(item -> item.get("available"))
                .containsExactly(true, false, true);
        assertThat(result.get(1).get("reason")).isEqualTo("原会话或消息已删除，或当前用户无权访问");
    }
}
