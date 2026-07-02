package com.example.aiapp.domain.dto;

import java.util.List;
import java.util.Map;

public record AiChatRequestDTO(
        String userId,
        String conversationId,
        String message,
        List<String> knowledgeBaseIds,
        boolean stream,
        Map<String, String> aiConfig
) {
}
