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
