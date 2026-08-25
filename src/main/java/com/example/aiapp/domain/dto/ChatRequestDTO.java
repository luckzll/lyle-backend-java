package com.example.aiapp.domain.dto;

import java.util.List;

public record ChatRequestDTO(
        String conversationId,
        String turnId,
        Long projectId,
        String message,
        List<String> knowledgeBaseIds,
        List<String> attachmentIds,
        boolean retry,
        String modelId
) {
    public ChatRequestDTO(
            String conversationId,
            String turnId,
            Long projectId,
            String message,
            List<String> knowledgeBaseIds,
            List<String> attachmentIds,
            boolean retry
    ) {
        this(conversationId, turnId, projectId, message, knowledgeBaseIds, attachmentIds, retry, null);
    }

    public ChatRequestDTO(
            String conversationId,
            String turnId,
            Long projectId,
            String message,
            List<String> knowledgeBaseIds,
            List<String> attachmentIds
    ) {
        this(conversationId, turnId, projectId, message, knowledgeBaseIds, attachmentIds, false, null);
    }

    public ChatRequestDTO(
            String conversationId,
            String turnId,
            Long projectId,
            String message,
            List<String> knowledgeBaseIds
    ) {
        this(conversationId, turnId, projectId, message, knowledgeBaseIds, List.of(), false, null);
    }
}
