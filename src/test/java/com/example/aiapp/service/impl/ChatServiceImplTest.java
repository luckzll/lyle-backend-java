package com.example.aiapp.service.impl;

import com.example.aiapp.domain.dto.AiChatRequestDTO;
import com.example.aiapp.domain.dto.AiChatResponseDTO;
import com.example.aiapp.domain.dto.AiChatAttachmentDTO;
import com.example.aiapp.domain.dto.ChatRequestDTO;
import com.example.aiapp.domain.entity.ChatMessageEntity;
import com.example.aiapp.domain.entity.ConversationEntity;
import com.example.aiapp.infrastructure.client.AiServiceClient;
import com.example.aiapp.mapper.ChatMessageMapper;
import com.example.aiapp.mapper.ConversationMapper;
import com.example.aiapp.service.AiConfigService;
import com.example.aiapp.service.ChatAttachmentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
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
                List.of(),
                Map.of(),
                Map.of()
        ));

        service.send(42L, new ChatRequestDTO(
                "100",
                null,
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
                "我已整理成待确认记忆，请确认后再写入长期记忆。",
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
                List.of(),
                Map.of(),
                Map.of()
        ));

        service.send(42L, new ChatRequestDTO(
                "100",
                null,
                null,
                "我不喜欢帽子",
                List.of()
        ));

        ArgumentCaptor<AiChatRequestDTO> captor = ArgumentCaptor.forClass(AiChatRequestDTO.class);
        verify(aiServiceClient).chat(captor.capture());
        assertThat(captor.getValue().message()).isEqualTo("我不喜欢帽子");
    }

    @Test
    void multiImageFollowUpCanAttachAllRequestedRecentImages() {
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
                message("assistant", "一组图片。"
                        + "\n![图片1](https://example.com/one.png)"
                        + "\n![图片2](https://example.com/two.png)"
                        + "\n![图片3](https://example.com/three.png)"
                        + "\n![图片4](https://example.com/four.png)"
                        + "\n![图片5](https://example.com/five.png)")
        ));
        when(aiServiceClient.chat(any())).thenReturn(new AiChatResponseDTO(
                "已完成。",
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
                List.of(),
                Map.of(),
                Map.of()
        ));

        service.send(42L, new ChatRequestDTO(
                "100",
                "turn-1",
                null,
                "结合上面四张图再生成 8 张",
                List.of()
        ));

        ArgumentCaptor<AiChatRequestDTO> captor = ArgumentCaptor.forClass(AiChatRequestDTO.class);
        verify(aiServiceClient).chat(captor.capture());
        assertThat(captor.getValue().message())
                .contains("https://example.com/two.png")
                .contains("https://example.com/three.png")
                .contains("https://example.com/four.png")
                .contains("https://example.com/five.png")
                .doesNotContain("https://example.com/one.png");
        assertThat(captor.getValue().turnId()).isEqualTo("turn-1");
    }

    @Test
    void streamPublishesTurnLifecycleAroundUpstreamEvents() {
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
        when(aiServiceClient.streamChat(any())).thenReturn(Flux.just(
                "event: delta\ndata: {\"text\":\"你好\"}\n\n",
                "event: done\ndata: {}\n\n"
        ));
        List<String> chunks = new ArrayList<>();

        service.stream(42L, new ChatRequestDTO(
                "100",
                "turn-stream",
                null,
                "你好",
                List.of()
        ), chunks::add);

        String stream = String.join("", chunks);
        assertThat(stream)
                .contains("\"phase\":\"THINKING\"")
                .contains("\"phase\":\"RESPONDING\"")
                .contains("\"phase\":\"COMPLETED\"")
                .contains("event: delta")
                .contains("\"text\":\"你好\"");
        ArgumentCaptor<ChatMessageEntity> saved = ArgumentCaptor.forClass(ChatMessageEntity.class);
        verify(chatMessageMapper, org.mockito.Mockito.times(2)).insert(saved.capture());
        assertThat(saved.getAllValues().get(1).getContent()).isEqualTo("你好");
    }

    @Test
    void streamCurrentImageAttachmentDoesNotAppendRecentHistoryImage() {
        AiServiceClient aiServiceClient = mock(AiServiceClient.class);
        AiConfigService aiConfigService = mock(AiConfigService.class);
        ChatMessageMapper chatMessageMapper = mock(ChatMessageMapper.class);
        ConversationMapper conversationMapper = mock(ConversationMapper.class);
        ChatAttachmentService chatAttachmentService = mock(ChatAttachmentService.class);
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(100L);
        conversation.setUserId(42L);
        when(conversationMapper.selectByIdAndUserId(100L, 42L)).thenReturn(conversation);
        when(aiConfigService.currentConfigMap()).thenReturn(Map.of());
        when(chatMessageMapper.selectByConversationId("100")).thenReturn(List.of(
                message("assistant", "历史紫色建筑图。\n![生成图片](https://example.com/purple-building.png)")
        ));
        AiChatAttachmentDTO currentImage = new AiChatAttachmentDTO(
                "current-image",
                "image",
                "current.png",
                "image/png",
                8,
                new AiChatAttachmentDTO.StorageObjectRefDTO(
                        "file:///tmp/current.png",
                        "LOCAL",
                        "chat-attachments",
                        "42/current.png",
                        "current.png",
                        "image/png",
                        8
                )
        );
        when(chatAttachmentService.requireReady(42L, List.of("current-image"))).thenReturn(List.of(currentImage));
        when(aiServiceClient.streamChat(any())).thenReturn(Flux.just(
                "event: delta\ndata: {\"text\":\"只分析本轮图片\"}\n\n",
                "event: done\ndata: {}\n\n"
        ));
        ChatServiceImpl service = new ChatServiceImpl(
                aiServiceClient,
                aiConfigService,
                chatMessageMapper,
                conversationMapper,
                new ObjectMapper()
        );
        service.setChatAttachmentService(chatAttachmentService);

        service.stream(42L, new ChatRequestDTO(
                "100",
                "turn-current-image",
                null,
                "请分析这张图片",
                List.of(),
                List.of("current-image")
        ), chunk -> {
        });

        ArgumentCaptor<AiChatRequestDTO> request = ArgumentCaptor.forClass(AiChatRequestDTO.class);
        verify(aiServiceClient).streamChat(request.capture());
        assertThat(request.getValue().message())
                .isEqualTo("请分析这张图片")
                .doesNotContain("purple-building.png");
        assertThat(request.getValue().attachments()).containsExactly(currentImage);
        verify(chatMessageMapper, never()).selectByConversationId("100");
    }

    @Test
    void retryFailedStreamReusesOriginalTurnUserMessageAndAttachments() {
        AiServiceClient aiServiceClient = mock(AiServiceClient.class);
        AiConfigService aiConfigService = mock(AiConfigService.class);
        ChatMessageMapper chatMessageMapper = mock(ChatMessageMapper.class);
        ConversationMapper conversationMapper = mock(ConversationMapper.class);
        ChatAttachmentService chatAttachmentService = mock(ChatAttachmentService.class);
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(100L);
        conversation.setUserId(42L);
        when(conversationMapper.selectByIdAndUserId(100L, 42L)).thenReturn(conversation);
        when(aiConfigService.currentConfigMap()).thenReturn(Map.of());

        ChatMessageEntity user = message("user", "请分析原附件");
        user.setId(98L);
        user.setTurnId("turn-retry");
        ChatMessageEntity assistant = message("assistant", "回复失败，请稍后重试。");
        assistant.setId(99L);
        assistant.setTurnId("turn-retry");
        assistant.setMetadataJson("{\"turnId\":\"turn-retry\",\"phase\":\"FAILED\",\"statusText\":\"回复失败\"}");
        when(chatMessageMapper.selectByConversationIdAndTurnId("100", "turn-retry")).thenReturn(assistant);
        when(chatMessageMapper.selectUserByConversationIdAndTurnId("100", "turn-retry")).thenReturn(user);

        AiChatAttachmentDTO attachment = new AiChatAttachmentDTO(
                "attachment-1",
                "image",
                "acceptance.png",
                "image/png",
                8,
                new AiChatAttachmentDTO.StorageObjectRefDTO(
                        "file:///tmp/acceptance.png",
                        "LOCAL",
                        "chat-attachments",
                        "42/attachment-1.png",
                        "acceptance.png",
                        "image/png",
                        8
                )
        );
        when(chatAttachmentService.requireAttachedForRetry(
                42L,
                "100",
                98L,
                List.of("attachment-1")
        )).thenReturn(List.of(attachment));
        when(aiServiceClient.streamChat(any())).thenReturn(Flux.just(
                "event: delta\ndata: {\"text\":\"重试成功\"}\n\n",
                "event: turn.status\ndata: {\"turnId\":\"turn-retry\",\"phase\":\"COMPLETED\",\"message\":\"回复完成\"}\n\n",
                "event: done\ndata: {}\n\n"
        ));

        ChatServiceImpl service = new ChatServiceImpl(
                aiServiceClient,
                aiConfigService,
                chatMessageMapper,
                conversationMapper,
                new ObjectMapper()
        );
        service.setChatAttachmentService(chatAttachmentService);

        service.stream(42L, new ChatRequestDTO(
                "100",
                "turn-retry",
                null,
                "被篡改的重试文字",
                List.of(),
                List.of("attachment-1"),
                true
        ), chunk -> {
        });

        ArgumentCaptor<AiChatRequestDTO> request = ArgumentCaptor.forClass(AiChatRequestDTO.class);
        verify(aiServiceClient).streamChat(request.capture());
        assertThat(request.getValue().turnId()).isEqualTo("turn-retry");
        assertThat(request.getValue().message()).isEqualTo("请分析原附件");
        assertThat(request.getValue().attachments()).containsExactly(attachment);
        assertThat(assistant.getContent()).isEqualTo("重试成功");
        assertThat(assistant.getMetadataJson()).contains("\"phase\":\"COMPLETED\"");
        verify(chatMessageMapper, never()).insert(any(ChatMessageEntity.class));
        verify(chatAttachmentService).requireAttachedForRetry(
                42L,
                "100",
                98L,
                List.of("attachment-1")
        );
    }

    @Test
    void streamDoesNotOverwriteUpstreamFailedTerminalState() {
        AiServiceClient aiServiceClient = mock(AiServiceClient.class);
        AiConfigService aiConfigService = mock(AiConfigService.class);
        ChatMessageMapper chatMessageMapper = mock(ChatMessageMapper.class);
        ConversationMapper conversationMapper = mock(ConversationMapper.class);
        when(aiConfigService.currentConfigMap()).thenReturn(Map.of());
        when(aiServiceClient.streamChat(any())).thenReturn(Flux.just(
                "event: turn.status\ndata: {\"turnId\":\"turn-failed\",\"phase\":\"FAILED\",\"message\":\"生成失败\"}\n\n",
                "event: done\ndata: {}\n\n"
        ));
        ChatServiceImpl service = new ChatServiceImpl(
                aiServiceClient,
                aiConfigService,
                chatMessageMapper,
                conversationMapper,
                new ObjectMapper()
        );
        List<String> chunks = new ArrayList<>();

        service.stream(42L, new ChatRequestDTO(
                "temporary-conversation",
                "turn-failed",
                null,
                "生成 4 张图片",
                List.of()
        ), chunks::add);

        String stream = String.join("", chunks);
        assertThat(stream).contains("\"phase\":\"FAILED\"");
        assertThat(stream).doesNotContain("\"phase\":\"COMPLETED\"");
    }

    @Test
    void streamPersistsIncrementalImageBatchStateForRefreshRecovery() {
        AiServiceClient aiServiceClient = mock(AiServiceClient.class);
        AiConfigService aiConfigService = mock(AiConfigService.class);
        ChatMessageMapper chatMessageMapper = mock(ChatMessageMapper.class);
        ConversationMapper conversationMapper = mock(ConversationMapper.class);
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(100L);
        conversation.setUserId(42L);
        when(conversationMapper.selectByIdAndUserId(100L, 42L)).thenReturn(conversation);
        when(aiConfigService.currentConfigMap()).thenReturn(Map.of());
        when(aiServiceClient.streamChat(any())).thenReturn(Flux.just(
                "event: turn.status\ndata: {\"turnId\":\"turn-image\",\"phase\":\"GENERATING\",\"message\":\"正在生成 2 张图片…\"}\n\n",
                "event: image.batch.started\ndata: {\"turnId\":\"turn-image\",\"batchId\":\"batch-1\",\"count\":2,\"mode\":\"generate\",\"items\":[{\"index\":0,\"status\":\"RUNNING\"},{\"index\":1,\"status\":\"RUNNING\"}]}\n\n",
                "event: image.item.completed\ndata: {\"turnId\":\"turn-image\",\"batchId\":\"batch-1\",\"index\":0,\"status\":\"COMPLETED\",\"imageUrl\":\"https://example.com/one.png\",\"completedCount\":1,\"count\":2}\n\n",
                "event: image.batch.completed\ndata: {\"turnId\":\"turn-image\",\"batchId\":\"batch-1\",\"status\":\"COMPLETED\",\"count\":2,\"completedCount\":2,\"failedCount\":0}\n\n",
                "event: delta\ndata: {\"text\":\"已生成 2 张图片。\"}\n\n",
                "event: turn.status\ndata: {\"turnId\":\"turn-image\",\"phase\":\"COMPLETED\",\"message\":\"已完成 2 张图片\"}\n\n",
                "event: done\ndata: {\"turnId\":\"turn-image\"}\n\n"
        ));
        ObjectMapper objectMapper = new ObjectMapper();
        ChatServiceImpl service = new ChatServiceImpl(
                aiServiceClient,
                aiConfigService,
                chatMessageMapper,
                conversationMapper,
                objectMapper
        );

        service.stream(42L, new ChatRequestDTO(
                "100",
                "turn-image",
                null,
                "生成 2 张图片",
                List.of()
        ), chunk -> {
        });

        ArgumentCaptor<ChatMessageEntity> saved = ArgumentCaptor.forClass(ChatMessageEntity.class);
        verify(chatMessageMapper, org.mockito.Mockito.times(2)).insert(saved.capture());
        ChatMessageEntity assistant = saved.getAllValues().get(1);
        assertThat(assistant.getTurnId()).isEqualTo("turn-image");
        assertThat(assistant.getContent()).isEqualTo("已生成 2 张图片。");
        assertThat(assistant.getMetadataJson())
                .contains("\"phase\":\"COMPLETED\"")
                .contains("\"batchId\":\"batch-1\"")
                .contains("https://example.com/one.png")
                .contains("\"completedCount\":2");
        verify(chatMessageMapper, atLeastOnce()).updateById(assistant);
    }

    @Test
    void failedImageStreamConvergesEveryUnfinishedItem() {
        AiServiceClient aiServiceClient = mock(AiServiceClient.class);
        AiConfigService aiConfigService = mock(AiConfigService.class);
        ChatMessageMapper chatMessageMapper = mock(ChatMessageMapper.class);
        ConversationMapper conversationMapper = mock(ConversationMapper.class);
        when(aiConfigService.currentConfigMap()).thenReturn(Map.of());
        when(aiServiceClient.streamChat(any())).thenReturn(Flux.just(
                "event: turn.status\ndata: {\"turnId\":\"turn-interrupted\",\"phase\":\"GENERATING\",\"message\":\"正在生成 2 张图片…\"}\n\n",
                "event: image.batch.started\ndata: {\"turnId\":\"turn-interrupted\",\"batchId\":\"batch-interrupted\",\"count\":2,\"mode\":\"generate\",\"items\":[{\"index\":0,\"status\":\"RUNNING\"},{\"index\":1,\"status\":\"RUNNING\"}]}\n\n",
                "event: image.item.completed\ndata: {\"turnId\":\"turn-interrupted\",\"batchId\":\"batch-interrupted\",\"index\":1,\"status\":\"COMPLETED\",\"imageUrl\":\"https://example.com/two.png\",\"completedCount\":1,\"count\":2}\n\n",
                "event: turn.status\ndata: {\"turnId\":\"turn-interrupted\",\"phase\":\"FAILED\",\"message\":\"图片任务执行失败\"}\n\n",
                "event: done\ndata: {\"turnId\":\"turn-interrupted\"}\n\n"
        ));
        ChatServiceImpl service = new ChatServiceImpl(
                aiServiceClient,
                aiConfigService,
                chatMessageMapper,
                conversationMapper,
                new ObjectMapper()
        );

        service.stream(42L, new ChatRequestDTO(
                "temporary-conversation",
                "turn-interrupted",
                null,
                "生成 2 张图片",
                List.of()
        ), chunk -> {
        });

        ArgumentCaptor<ChatMessageEntity> saved = ArgumentCaptor.forClass(ChatMessageEntity.class);
        verify(chatMessageMapper, org.mockito.Mockito.times(2)).insert(saved.capture());
        ChatMessageEntity assistant = saved.getAllValues().get(1);
        assertThat(assistant.getMetadataJson())
                .contains("\"phase\":\"FAILED\"")
                .contains("\"status\":\"PARTIALLY_COMPLETED\"")
                .contains("\"completedCount\":1")
                .contains("\"failedCount\":1")
                .contains("\"index\":0,\"status\":\"FAILED\"")
                .doesNotContain("\"status\":\"RUNNING\"");
    }

    @Test
    void cancelTurnPersistsCancelledBatchAndAssistantMessage() {
        AiServiceClient aiServiceClient = mock(AiServiceClient.class);
        AiConfigService aiConfigService = mock(AiConfigService.class);
        ChatMessageMapper chatMessageMapper = mock(ChatMessageMapper.class);
        ConversationMapper conversationMapper = mock(ConversationMapper.class);
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(100L);
        conversation.setUserId(42L);
        when(conversationMapper.selectByIdAndUserId(100L, 42L)).thenReturn(conversation);
        ChatMessageEntity assistant = message("assistant", "");
        assistant.setId(99L);
        assistant.setTurnId("turn-cancel");
        assistant.setMetadataJson("""
                {"turnId":"turn-cancel","phase":"GENERATING","statusText":"正在生成 2 张图片…",\
                "imageBatch":{"batchId":"batch-cancel","count":2,"mode":"generate","status":"RUNNING",\
                "completedCount":0,"failedCount":0,"items":[{"index":0,"status":"RUNNING"},{"index":1,"status":"QUEUED"}]}}
                """);
        when(chatMessageMapper.selectByConversationIdAndTurnId("100", "turn-cancel")).thenReturn(assistant);
        ChatServiceImpl service = new ChatServiceImpl(
                aiServiceClient,
                aiConfigService,
                chatMessageMapper,
                conversationMapper,
                new ObjectMapper()
        );

        var result = service.cancelTurn(42L, "100", "turn-cancel");

        assertThat(result.content()).isEqualTo("已停止生成。");
        assertThat(result.metadata().get("phase")).isEqualTo("FAILED");
        assertThat(result.metadata().toString()).contains("status=CANCELLED");
        verify(chatMessageMapper).updateById(assistant);
    }

    @Test
    void cancellingActiveTurnInterruptsUpstreamButKeepsCancelledState() throws Exception {
        AiServiceClient aiServiceClient = mock(AiServiceClient.class);
        AiConfigService aiConfigService = mock(AiConfigService.class);
        ChatMessageMapper chatMessageMapper = mock(ChatMessageMapper.class);
        ConversationMapper conversationMapper = mock(ConversationMapper.class);
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(100L);
        conversation.setUserId(42L);
        when(conversationMapper.selectByIdAndUserId(100L, 42L)).thenReturn(conversation);
        when(aiConfigService.currentConfigMap()).thenReturn(Map.of());
        when(aiServiceClient.streamChat(any())).thenReturn(Flux.concat(
                Flux.just(
                        "event: turn.status\ndata: {\"turnId\":\"turn-active\",\"phase\":\"GENERATING\",\"message\":\"正在生成 2 张图片…\"}\n\n",
                        "event: image.batch.started\ndata: {\"turnId\":\"turn-active\",\"batchId\":\"batch-active\",\"count\":2,\"mode\":\"generate\",\"items\":[{\"index\":0,\"status\":\"RUNNING\"},{\"index\":1,\"status\":\"RUNNING\"}]}\n\n"
                ),
                Flux.never()
        ));
        CountDownLatch batchPersisted = new CountDownLatch(1);
        doAnswer(invocation -> {
            ChatMessageEntity entity = invocation.getArgument(0);
            if (entity.getMetadataJson() != null && entity.getMetadataJson().contains("batch-active")) {
                batchPersisted.countDown();
            }
            return 1;
        }).when(chatMessageMapper).updateById(any(ChatMessageEntity.class));
        ChatServiceImpl service = new ChatServiceImpl(
                aiServiceClient,
                aiConfigService,
                chatMessageMapper,
                conversationMapper,
                new ObjectMapper()
        );
        Thread worker = new Thread(() -> service.stream(42L, new ChatRequestDTO(
                "100",
                "turn-active",
                null,
                "生成 2 张图片",
                List.of()
        ), chunk -> {
        }));
        worker.start();
        assertThat(batchPersisted.await(2, TimeUnit.SECONDS)).isTrue();

        var result = service.cancelTurn(42L, "100", "turn-active");
        worker.join(2000);

        assertThat(worker.isAlive()).isFalse();
        assertThat(result.content()).isEqualTo("已停止生成。");
        assertThat(result.metadata().toString()).contains("status=CANCELLED");
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
