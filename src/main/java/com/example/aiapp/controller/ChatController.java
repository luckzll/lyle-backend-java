package com.example.aiapp.controller;

import com.example.aiapp.domain.dto.ChatRequestDTO;
import com.example.aiapp.domain.vo.ChatMessageVO;
import com.example.aiapp.domain.vo.ChatResponseVO;
import com.example.aiapp.service.ChatService;
import com.example.aiapp.utils.CurrentUserUtils;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final Object STREAM_END = new Object();

    private final ChatService chatService;
    private final Executor chatGenerationExecutor;

    public ChatController(
            ChatService chatService,
            @Qualifier("chatGenerationExecutor") Executor chatGenerationExecutor
    ) {
        this.chatService = chatService;
        this.chatGenerationExecutor = chatGenerationExecutor;
    }

    @GetMapping("/health")
    Map<String, String> health() {
        return Map.of("status", "ok", "service", "ai-app-backend");
    }

    @GetMapping("/chat/messages/{conversationId}")
    List<ChatMessageVO> messages(@PathVariable String conversationId) {
        return chatService.listMessages(CurrentUserUtils.currentUserId(), conversationId);
    }

    @PostMapping("/chat/send")
    ChatResponseVO send(@Valid @RequestBody ChatRequestDTO request) {
        return chatService.send(CurrentUserUtils.currentUserId(), request);
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    StreamingResponseBody stream(@Valid @RequestBody ChatRequestDTO request) {
        Long userId = CurrentUserUtils.currentUserId();
        StreamSession session = new StreamSession();
        chatGenerationExecutor.execute(() -> {
            try {
                chatService.stream(userId, request, session::publish);
            } catch (RuntimeException exception) {
                log.warn(
                        "Detached chat generation failed. userId={} conversationId={} turnId={}",
                        userId,
                        request.conversationId(),
                        request.turnId(),
                        exception
                );
            } finally {
                session.complete();
            }
        });
        return session.responseBody();
    }

    private static final class StreamSession {
        private final BlockingQueue<Object> frames = new LinkedBlockingQueue<>();
        private final AtomicBoolean clientDisconnected = new AtomicBoolean(false);

        void publish(String chunk) {
            if (!clientDisconnected.get() && chunk != null) {
                frames.offer(chunk);
            }
        }

        void complete() {
            frames.offer(STREAM_END);
        }

        StreamingResponseBody responseBody() {
            return outputStream -> {
                while (true) {
                    Object frame;
                    try {
                        frame = frames.take();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        clientDisconnected.set(true);
                        return;
                    }
                    if (frame == STREAM_END) {
                        return;
                    }
                    try {
                        outputStream.write(String.valueOf(frame).getBytes(StandardCharsets.UTF_8));
                        outputStream.flush();
                    } catch (Exception exception) {
                        clientDisconnected.set(true);
                        return;
                    }
                }
            };
        }
    }

    @PostMapping("/chat/conversations/{conversationId}/turns/{turnId}/cancel")
    ChatMessageVO cancelTurn(@PathVariable String conversationId, @PathVariable String turnId) {
        return chatService.cancelTurn(CurrentUserUtils.currentUserId(), conversationId, turnId);
    }
}
