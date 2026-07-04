package com.example.aiapp.service.impl;

import com.example.aiapp.domain.dto.AiChatRequestDTO;
import com.example.aiapp.domain.dto.AiChatResponseDTO;
import com.example.aiapp.domain.dto.ChatRequestDTO;
import com.example.aiapp.domain.entity.ChatMessageEntity;
import com.example.aiapp.domain.entity.ConversationEntity;
import com.example.aiapp.infrastructure.client.AiServiceClient;
import com.example.aiapp.mapper.ChatMessageMapper;
import com.example.aiapp.mapper.ConversationMapper;
import com.example.aiapp.service.AiConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceImplTest {

    @Test
    void imageFollowUpCanSelectPreviousCartoonImageInsteadOfLatestImage() {
        AiServiceClient aiServiceClient = mock(AiServiceClient.class);
        AiConfigService aiConfigService = mock(AiConfigService.class);
        ChatMessageMapper chatMessageMapper = mock(ChatMessageMapper.class);
        ConversationMapper conversationMapper = mock(ConversationMapper.class);
        ChatServiceImpl service = new ChatServiceImpl(
                aiServiceClient,
                aiConfigService,
                chatMessageMapper,
                conversationMapper,
                new ObjectMapper()
        );
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(100L);
        conversation.setUserId(42L);
        when(conversationMapper.selectByIdAndUserId(100L, 42L)).thenReturn(conversation);
        when(aiConfigService.currentConfigMap()).thenReturn(Map.of());
        when(chatMessageMapper.selectByConversationId("100")).thenReturn(List.of(
                message("assistant", "卡通图已生成。\n![生成图片](https://example.com/cartoon.png)"),
                message("assistant", "图片已生成。\n![生成图片](https://example.com/person.png)")
        ));
        when(aiServiceClient.chat(any())).thenReturn(new AiChatResponseDTO(
                "图片已修改。",
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                Map.of()
        ));

        service.send(42L, new ChatRequestDTO(
                "100",
                9L,
                "修改上一张卡通图，去掉帽子",
                List.of()
        ));

        ArgumentCaptor<AiChatRequestDTO> captor = ArgumentCaptor.forClass(AiChatRequestDTO.class);
        verify(aiServiceClient).chat(captor.capture());
        assertThat(captor.getValue().message()).contains("https://example.com/cartoon.png");
        assertThat(captor.getValue().message()).doesNotContain("https://example.com/person.png");
        assertThat(captor.getValue().projectId()).isEqualTo(9L);
    }

    @Test
    void plainDislikePreferenceDoesNotAttachRecentImageContext() {
        AiServiceClient aiServiceClient = mock(AiServiceClient.class);
        AiConfigService aiConfigService = mock(AiConfigService.class);
        ChatMessageMapper chatMessageMapper = mock(ChatMessageMapper.class);
        ConversationMapper conversationMapper = mock(ConversationMapper.class);
        ChatServiceImpl service = new ChatServiceImpl(
                aiServiceClient,
                aiConfigService,
                chatMessageMapper,
                conversationMapper,
                new ObjectMapper()
        );
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(100L);
        conversation.setUserId(42L);
        when(conversationMapper.selectByIdAndUserId(100L, 42L)).thenReturn(conversation);
        when(aiConfigService.currentConfigMap()).thenReturn(Map.of());
        when(chatMessageMapper.selectByConversationId("100")).thenReturn(List.of(
                message("assistant", "图片已生成。\n![生成图片](https://example.com/person.png)")
        ));
        when(aiServiceClient.chat(any())).thenReturn(new AiChatResponseDTO(
                "记住了。",
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                Map.of()
        ));

        service.send(42L, new ChatRequestDTO(
                "100",
                null,
                "我不喜欢帽子",
                List.of()
        ));

        ArgumentCaptor<AiChatRequestDTO> captor = ArgumentCaptor.forClass(AiChatRequestDTO.class);
        verify(aiServiceClient).chat(captor.capture());
        assertThat(captor.getValue().message()).isEqualTo("我不喜欢帽子");
    }

    private ChatMessageEntity message(String role, String content) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setConversationId("100");
        entity.setRole(role);
        entity.setContent(content);
        entity.setCreatedAt(LocalDateTime.now());
        return entity;
    }
}
