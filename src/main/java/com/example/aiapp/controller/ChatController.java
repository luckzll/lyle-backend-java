package com.example.aiapp.controller;

import com.example.aiapp.domain.dto.ChatRequestDTO;
import com.example.aiapp.domain.vo.ChatMessageVO;
import com.example.aiapp.domain.vo.ChatResponseVO;
import com.example.aiapp.service.ChatService;
import com.example.aiapp.utils.CurrentUserUtils;
import jakarta.validation.Valid;
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

@RestController
@RequestMapping
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
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
        return outputStream -> chatService.stream(userId, request, chunk -> {
            try {
                outputStream.write(chunk.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            } catch (Exception exception) {
                throw new IllegalStateException("Failed to write chat stream.", exception);
            }
        });
    }
}
