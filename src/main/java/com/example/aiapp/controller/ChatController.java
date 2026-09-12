package com.example.aiapp.controller;

import com.example.aiapp.domain.dto.ChatRequestDTO;
import com.example.aiapp.domain.vo.ChatMessageVO;
import com.example.aiapp.domain.vo.ChatResponseVO;
import com.example.aiapp.service.ChatService;
import com.example.aiapp.utils.CurrentUserUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI 对话接口。
 */
@RestController
@RequestMapping
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final Object STREAM_END = new Object();
    private static final ObjectMapper EVENT_JSON = new ObjectMapper();
    private static final Set<String> SAFE_REQUEST_ERRORS = Set.of(
            "知识库不存在", "会话不存在", "要重试的对话轮次不存在", "要重试的用户消息不存在",
            "只能重试失败或已停止的轮次", "消息或附件不能为空", "不支持的对话模型",
            "只能重试已保存会话中的附件", "重试附件无效", "重试必须保留原消息的全部附件和顺序",
            "单轮附件总量不能超过 20MB", "附件只能绑定到已保存的会话", "附件不存在",
            "单轮最多 8 个附件，且不能重复", "附件未就绪或不属于当前用户",
            "今日 AI 用量已达上限，额度将在明日 00:00 恢复。",
            "当前 AI 额度不足，请升级套餐或购买额度后主动重试。"
    );

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
                session.failIfNeeded(request.turnId(), safeFailureMessage(exception));
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

    private static String safeFailureMessage(RuntimeException exception) {
        if (exception instanceof ResponseStatusException rejected) {
            if (rejected.getReason() != null && SAFE_REQUEST_ERRORS.contains(rejected.getReason())) {
                return rejected.getReason();
            }
            return switch (rejected.getStatusCode().value()) {
                case 401 -> "登录状态已失效，请重新登录。";
                case 403, 404 -> "会话或相关资料不可用，请刷新后重新选择。";
                case 402 -> "当前 AI 额度不足，请检查套餐与额度。";
                case 400, 409 -> "本次请求无法执行，请检查消息和附件后重试。";
                case 429 -> "当前请求过多或用量已达上限，请稍后重试。";
                default -> "AI 服务暂时无法处理本次请求，请稍后重试。";
            };
        }
        return "AI 服务暂时无法处理本次请求，请稍后重试。";
    }

    static final class StreamSession {
        private final BlockingQueue<Object> frames = new LinkedBlockingQueue<>(256);
        private final AtomicBoolean clientDisconnected = new AtomicBoolean(false);
        private final AtomicBoolean completed = new AtomicBoolean(false);
        private final AtomicBoolean terminalPublished = new AtomicBoolean(false);
        private final AtomicInteger queuedChars = new AtomicInteger();
        private final long heartbeatMillis;

        StreamSession() {
            this(10_000);
        }

        StreamSession(long heartbeatMillis) {
            this.heartbeatMillis = heartbeatMillis;
        }

        void publish(String chunk) {
            if (chunk != null && (chunk.startsWith("event: done\n") || chunk.startsWith("event: error\n")
                    || (chunk.startsWith("event: turn.status\n") && chunk.contains("\"phase\":\"FAILED\"")))) {
                terminalPublished.set(true);
            }
            if (clientDisconnected.get() || completed.get() || chunk == null) return;
            if (queuedChars.addAndGet(chunk.length()) > 1_048_576 || !frames.offer(chunk)) {
                // A slow client must not block generation or grow an unbounded queue.
                // Close only this subscription; the durable turn can be recovered by polling.
                clientDisconnected.set(true);
                frames.clear();
                queuedChars.set(0);
                frames.offer(STREAM_END);
            }
        }

        void failIfNeeded(String turnId, String message) {
            if (terminalPublished.get()) return;
            try {
                Map<String, String> failure = Map.of("turnId", turnId == null ? "" : turnId, "phase", "FAILED", "message", message);
                publish("event: turn.status\ndata: " + EVENT_JSON.writeValueAsString(failure) + "\n\n");
                publish("event: error\ndata: " + EVENT_JSON.writeValueAsString(Map.of("message", message)) + "\n\n");
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                throw new IllegalStateException("Failed to serialize chat failure status", exception);
            }
        }

        void complete() {
            completed.set(true);
            frames.offer(STREAM_END);
        }

        StreamingResponseBody responseBody() {
            return outputStream -> {
                while (true) {
                    if (clientDisconnected.get() || (completed.get() && frames.isEmpty())) {
                        return;
                    }
                    Object frame;
                    try {
                        frame = frames.poll(heartbeatMillis, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        clientDisconnected.set(true);
                        frames.clear();
                        queuedChars.set(0);
                        return;
                    }
                    if (frame == STREAM_END) {
                        return;
                    }
                    if (frame instanceof String text) {
                        queuedChars.updateAndGet(count -> Math.max(0, count - text.length()));
                    }
                    try {
                        outputStream.write((frame == null ? ": keep-alive\n\n" : String.valueOf(frame))
                                .getBytes(StandardCharsets.UTF_8));
                        outputStream.flush();
                    } catch (Exception exception) {
                        clientDisconnected.set(true);
                        frames.clear();
                        queuedChars.set(0);
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
