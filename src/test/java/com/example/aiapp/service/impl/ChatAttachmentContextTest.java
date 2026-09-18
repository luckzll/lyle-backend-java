package com.example.aiapp.service.impl;

import com.example.aiapp.config.JacksonConfig;
import com.example.aiapp.domain.dto.AiChatRequestDTO;
import com.example.aiapp.domain.dto.AiChatResponseDTO;
import com.example.aiapp.domain.dto.ChatRequestDTO;
import com.example.aiapp.domain.entity.ChatAttachmentEntity;
import com.example.aiapp.domain.entity.ChatMessageEntity;
import com.example.aiapp.domain.entity.ConversationEntity;
import com.example.aiapp.infrastructure.client.AiServiceClient;
import com.example.aiapp.infrastructure.storage.FileStorageService;
import com.example.aiapp.mapper.ChatAttachmentMapper;
import com.example.aiapp.mapper.ChatMessageMapper;
import com.example.aiapp.mapper.ConversationMapper;
import com.example.aiapp.service.AiConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChatAttachmentContextTest {
    @Test
    void followUpInheritsFilesWithoutDuplicatingUploadsAndPreservesSourceInLiveHistoryReplay() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.attachments.selectRecentAttachedForConversation(42L, 100L, null))
                .thenReturn(List.of(file("old", "ATTACHED", 98L)));
        fixture.run(List.of(), false);
        assertThat(fixture.request().attachments()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo("old");
            assertThat(item.sourceMessageId()).isEqualTo("98");
        });
        verify(fixture.attachments, never()).updateById(any(ChatAttachmentEntity.class));
        verify(fixture.attachments, never()).selectByUserIdAndIds(any(), any());
        assertThat(String.join("", fixture.frames)).contains("\"attachmentContextInherited\":true", "\"attachmentContextSourceMessageId\":\"98\"");
        ChatMessageEntity assistant = fixture.messagesSaved.get(1);
        assertThat(fixture.json.readTree(assistant.getMetadataJson()).path("attachmentContextSourceMessageId").asText()).isEqualTo("98");
        when(fixture.messages.selectByConversationId("100")).thenReturn(fixture.messagesSaved);
        var history = fixture.service.listMessages(42L, "100");
        assertThat(history.get(1).metadata()).containsEntry("attachmentContextInherited", true)
                .containsEntry("attachmentContextSourceMessageId", "98");
        assertThat(history.get(0).attachments()).isEmpty();
        when(fixture.messages.selectByConversationIdAndTurnId("100", "turn-context")).thenReturn(assistant);
        fixture.frames.clear();
        fixture.run(List.of(), false);
        assertThat(String.join("", fixture.frames)).contains("\"attachmentContextInherited\":true", "\"attachmentContextSourceMessageId\":\"98\"");
        verify(fixture.client, times(1)).streamChat(any());
    }

    @Test
    void newlyUploadedFilesReplaceEarlierContextAndAreBoundOnlyOnce() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.attachments.selectByUserIdAndIds(42L, List.of("new"))).thenReturn(List.of(file("new", "READY", null)));
        fixture.run(List.of("new"), false);
        assertThat(fixture.request().attachments()).extracting(item -> item.id()).containsExactly("new");
        verify(fixture.attachments, never()).selectRecentAttachedForConversation(any(), any(), any());
        verify(fixture.attachments, times(1)).updateById(any(ChatAttachmentEntity.class));
        var metadata = fixture.json.readTree(fixture.messagesSaved.get(1).getMetadataJson());
        assertThat(metadata.path("attachmentContextInherited").asBoolean()).isFalse();
        assertThat(metadata.path("attachmentContextSourceMessageId").asText()).isEqualTo("201");
    }

    @Test
    void noAttachmentOrImageOnlyHistoryDoesNotAddFilesOrImageReferencesToWeatherQuestion() {
        for (boolean imageOnly : List.of(false, true)) {
            Fixture fixture = new Fixture();
            ChatAttachmentEntity image = file("image", "ATTACHED", 98L);
            image.setKind("image");
            when(fixture.attachments.selectRecentAttachedForConversation(42L, 100L, null))
                    .thenReturn(imageOnly ? List.of(image) : List.of());
            fixture.run(List.of(), false);
            assertThat(fixture.request().attachments()).isEmpty();
            assertThat(fixture.request().message()).isEqualTo("北京天气怎样？");
            assertThat(String.join("", fixture.frames)).doesNotContain("attachmentContextInherited", "file://");
        }
    }

    @Test
    void retryOfInheritedTurnUsesTheOriginalMessageBoundaryAndDoesNotRebind() {
        Fixture fixture = new Fixture();
        ChatMessageEntity originalUser = new ChatMessageEntity();
        originalUser.setId(150L);
        originalUser.setContent("原始问题");
        ChatMessageEntity assistant = new ChatMessageEntity();
        assistant.setRole("assistant");
        assistant.setMetadataJson("{\"phase\":\"FAILED\"}");
        when(fixture.messages.selectByConversationIdAndTurnId("100", "turn-context")).thenReturn(assistant);
        when(fixture.messages.selectUserByConversationIdAndTurnId("100", "turn-context")).thenReturn(originalUser);
        when(fixture.attachments.selectRecentAttachedForConversation(42L, 100L, 150L))
                .thenReturn(List.of(file("old", "ATTACHED", 98L)));
        fixture.run(List.of(), true);
        assertThat(fixture.request().message()).isEqualTo("原始问题");
        assertThat(fixture.request().attachments()).extracting(item -> item.id()).containsExactly("old");
        verify(fixture.attachments, never()).selectRecentAttachedForConversation(42L, 100L, null);
        verify(fixture.attachments, never()).updateById(any(ChatAttachmentEntity.class));
        verify(fixture.messages, never()).insert(any(ChatMessageEntity.class));
    }

    @Test
    void synchronousSendUsesTheSameContextAndUnownedConversationIsRejectedBeforeLookup() {
        Fixture fixture = new Fixture();
        when(fixture.attachments.selectRecentAttachedForConversation(42L, 100L, null))
                .thenReturn(List.of(file("old", "ATTACHED", 98L)));
        AiChatResponseDTO answer = mock(AiChatResponseDTO.class);
        when(answer.answer()).thenReturn("分析完成");
        when(fixture.client.chat(any())).thenReturn(answer);
        fixture.service.send(42L, new ChatRequestDTO("100", "sync-context", null, "继续分析", List.of()));
        ArgumentCaptor<AiChatRequestDTO> captured = ArgumentCaptor.forClass(AiChatRequestDTO.class);
        verify(fixture.client).chat(captured.capture());
        assertThat(captured.getValue().attachments()).extracting(item -> item.id()).containsExactly("old");
        assertThat(fixture.messagesSaved.get(1).getMetadataJson()).contains("\"attachmentContextInherited\":true");
        clearInvocations(fixture.attachments);
        assertThatThrownBy(() -> fixture.service.stream(7L,
                new ChatRequestDTO("100", "unowned", null, "继续分析", List.of()), frame -> {})).hasMessageContaining("会话不存在");
        verifyNoInteractions(fixture.attachments);
    }

    private static ChatAttachmentEntity file(String id, String status, Long messageId) {
        ChatAttachmentEntity entity = new ChatAttachmentEntity();
        entity.setId(id);
        entity.setUserId(42L);
        entity.setConversationId("ATTACHED".equals(status) ? 100L : null);
        entity.setMessageId(messageId);
        entity.setKind("file");
        entity.setStatus(status);
        entity.setFileSize(10L);
        entity.setOriginalName("Main.java");
        entity.setContentType("text/plain");
        entity.setStorageProvider("LOCAL");
        entity.setStoragePath("file:///tmp/" + id + ".java");
        entity.setBucket("chat-attachments");
        entity.setObjectKey("42/" + id + ".java");
        return entity;
    }

    private static class Fixture {
        final ObjectMapper json;
        final AiServiceClient client = mock(AiServiceClient.class);
        final ChatMessageMapper messages = mock(ChatMessageMapper.class);
        final ChatAttachmentMapper attachments = mock(ChatAttachmentMapper.class);
        final List<ChatMessageEntity> messagesSaved = new ArrayList<>();
        final List<String> frames = new ArrayList<>();
        final ChatServiceImpl service;

        Fixture() {
            try (var context = new AnnotationConfigApplicationContext(JacksonConfig.class, JacksonAutoConfiguration.class)) {
                json = context.getBean(ObjectMapper.class);
            }
            AiConfigService config = mock(AiConfigService.class);
            when(config.currentConfigMap()).thenReturn(Map.of());
            ConversationMapper conversations = mock(ConversationMapper.class);
            ConversationEntity conversation = new ConversationEntity();
            conversation.setId(100L);
            conversation.setUserId(42L);
            when(conversations.selectByIdAndUserId(100L, 42L)).thenReturn(conversation);
            service = new ChatServiceImpl(client, config, messages, conversations, json);
            service.setChatAttachmentService(new ChatAttachmentServiceImpl(attachments, mock(FileStorageService.class)));
            AtomicLong ids = new AtomicLong(200);
            doAnswer(invocation -> {
                ChatMessageEntity entity = invocation.getArgument(0);
                entity.setId(ids.incrementAndGet());
                messagesSaved.add(entity);
                return 1;
            }).when(messages).insert(any(ChatMessageEntity.class));
            when(client.streamChat(any())).thenReturn(Flux.just(
                    "event: meta\ndata: {\"attachmentContextInherited\":false,\"attachmentContextSourceMessageId\":\"999\"}\n\n",
                    "event: delta\ndata: {\"text\":\"分析完成\"}\n\n", "event: done\ndata: {}\n\n"));
        }
        void run(List<String> ids, boolean retry) {
            service.stream(42L, new ChatRequestDTO("100", "turn-context", null, "北京天气怎样？", List.of(), ids, retry), frames::add);
        }
        AiChatRequestDTO request() {
            ArgumentCaptor<AiChatRequestDTO> captured = ArgumentCaptor.forClass(AiChatRequestDTO.class);
            verify(client).streamChat(captured.capture());
            return captured.getValue();
        }
    }
}
