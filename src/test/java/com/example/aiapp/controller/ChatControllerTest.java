package com.example.aiapp.controller;

import com.example.aiapp.domain.dto.ChatRequestDTO;
import com.example.aiapp.security.CurrentUser;
import com.example.aiapp.service.ChatService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class ChatControllerTest {

    @Test
    void preflightRejectionPublishesExplicitFailureWithoutADurableTurn() throws Exception {
        for (var failure : List.of(
                new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "知识库不存在"),
                new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.PAYMENT_REQUIRED, "当前 AI 额度不足，请升级套餐或购买额度后主动重试。"),
                new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "单轮普通附件最多 8 个，目录内文件最多 200 个"),
                new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "单轮图片最多 8 张（含目录内图片）"),
                new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "文本附件必须使用 UTF-8 编码"),
                new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "请选择非空附件")
        )) {
            ChatService service = mock(ChatService.class);
            org.mockito.Mockito.doThrow(failure).when(service).stream(eq(42L), any(), any());
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(new CurrentUser(42L, "lyle", "USER"), null));
            try {
                var response = new ChatController(service, Runnable::run).stream(new ChatRequestDTO("100", "turn-rejected", null, "你好", List.of()));
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                response.writeTo(output);
                assertThat(output.toString(java.nio.charset.StandardCharsets.UTF_8))
                        .contains("event: turn.status", "\"phase\":\"FAILED\"", "turn-rejected", failure.getReason(), "event: error")
                        .doesNotContain("event: done");
            } finally {
                SecurityContextHolder.clearContext();
            }
        }
    }

    @Test
    void unexpectedFailureDoesNotExposeSqlOrCredentialsAndPreservesAnExistingTerminalEvent() throws Exception {
        for (boolean alreadyFailed : List.of(false, true)) {
            ChatService service = mock(ChatService.class);
            doAnswer(invocation -> {
                if (alreadyFailed) {
                    Consumer<String> publish = invocation.getArgument(2);
                    publish.accept("event: turn.status\ndata: {\"phase\":\"FAILED\",\"message\":\"AI 服务响应超时\"}\n\n");
                }
                throw new IllegalStateException("SELECT credential FROM secrets; password=PRIVATE");
            }).when(service).stream(eq(42L), any(), any());
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(new CurrentUser(42L, "lyle", "USER"), null));
            try {
                var response = new ChatController(service, Runnable::run).stream(new ChatRequestDTO("100", "turn-rejected", null, "你好", List.of()));
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                response.writeTo(output);
                String text = output.toString(java.nio.charset.StandardCharsets.UTF_8);
                assertThat(text).contains("\"phase\":\"FAILED\"").doesNotContain("PRIVATE", "SELECT", "credential");
                if (alreadyFailed) assertThat(text).contains("AI 服务响应超时").doesNotContain("暂时无法处理");
            } finally {
                SecurityContextHolder.clearContext();
            }
        }
    }

    @Test
    void waitingResponseEmitsHeartbeatWithoutManufacturingProgress() throws Exception {
        ChatController.StreamSession session = new ChatController.StreamSession(5);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream() {
            @Override
            public void flush() {
                session.complete();
            }
        };
        session.responseBody().writeTo(bytes);
        assertThat(bytes.toString(java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(": keep-alive\n\n");
    }

    @Test
    void slowSubscriberOverflowClosesSubscriptionWithoutBlockingProducer() throws Exception {
        ChatController.StreamSession session = new ChatController.StreamSession(5);
        for (int index = 0; index < 10_000; index++) session.publish("event: delta\ndata: {}\n\n");
        session.complete();
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        session.responseBody().writeTo(bytes);
        assertThat(bytes.size()).isZero();
    }

    @Test
    void largeFramesCannotBypassTheSubscriberMemoryBudget() throws Exception {
        ChatController.StreamSession session = new ChatController.StreamSession(5);
        session.publish("x".repeat(600_000));
        session.publish("y".repeat(600_000));
        session.complete();
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        session.responseBody().writeTo(bytes);
        assertThat(bytes.size()).isZero();
    }

    @Test
    void brokenClientConnectionDoesNotCancelDetachedGeneration() throws Exception {
        ChatService chatService = mock(ChatService.class);
        ExecutorService generationExecutor = Executors.newSingleThreadExecutor();
        CountDownLatch firstFramePublished = new CountDownLatch(1);
        CountDownLatch continueGeneration = new CountDownLatch(1);
        CountDownLatch generationFinished = new CountDownLatch(1);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<String> consumer = invocation.getArgument(2, Consumer.class);
            consumer.accept("event: turn.status\ndata: {\"phase\":\"GENERATING\"}\n\n");
            firstFramePublished.countDown();
            if (!continueGeneration.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test generation was not released");
            }
            consumer.accept("event: done\ndata: {}\n\n");
            generationFinished.countDown();
            return null;
        }).when(chatService).stream(eq(42L), any(ChatRequestDTO.class), any());

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(42L, "lyle", "USER"), null)
        );
        try {
            ChatController controller = new ChatController(chatService, generationExecutor);
            var responseBody = controller.stream(new ChatRequestDTO(
                    "100",
                    "turn-refresh",
                    null,
                    "生成 2 张图片",
                    List.of()
            ));
            assertThat(firstFramePublished.await(2, TimeUnit.SECONDS)).isTrue();

            responseBody.writeTo(new OutputStream() {
                @Override
                public void write(int value) throws IOException {
                    throw new IOException("Broken pipe");
                }
            });

            assertThat(generationFinished.getCount()).isEqualTo(1);
            continueGeneration.countDown();
            assertThat(generationFinished.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            continueGeneration.countDown();
            generationExecutor.shutdownNow();
            SecurityContextHolder.clearContext();
        }
    }
}
