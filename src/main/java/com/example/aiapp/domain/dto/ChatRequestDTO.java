package com.example.aiapp.domain.dto;

import java.util.List;

public record ChatRequestDTO(
        String conversationId,
        String turnId,
        Long projectId,
        String message,
        List<String> knowledgeBaseIds,
        List<String> attachmentIds,
        boolean retry
) {
    public ChatRequestDTO(
            String conversationId,
            String turnId,
            Long projectId,
            String message,
            List<String> knowledgeBaseIds,
            List<String> attachmentIds
    ) {
        this(conversationId, turnId, projectId, message, knowledgeBaseIds, attachmentIds, false);
    }

    public ChatRequestDTO(
            String conversationId,
            String turnId,
            Long projectId,
            String message,
            List<String> knowledgeBaseIds
    ) {
        this(conversationId, turnId, projectId, message, knowledgeBaseIds, List.of(), false);
    }
}
