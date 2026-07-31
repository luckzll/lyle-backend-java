package com.example.aiapp.domain.dto;

import java.util.List;
import java.util.Map;

public record AiChatRequestDTO(
        String userId,
        String conversationId,
        String turnId,
        Long projectId,
        String message,
        List<String> knowledgeBaseIds,
        boolean stream,
        Map<String, String> aiConfig,
        List<AiChatAttachmentDTO> attachments,
        String traceId,
        String reservationId,
        Map<String, Long> usageBudget
) {
    public AiChatRequestDTO(
            String userId,
            String conversationId,
            String turnId,
            Long projectId,
            String message,
            List<String> knowledgeBaseIds,
            boolean stream,
            Map<String, String> aiConfig,
            List<AiChatAttachmentDTO> attachments
    ) {
        this(
                userId,
                conversationId,
                turnId,
                projectId,
                message,
                knowledgeBaseIds,
                stream,
                aiConfig,
                attachments,
                null,
                null,
                Map.of()
        );
    }

    public AiChatRequestDTO(
            String userId,
            String conversationId,
            String turnId,
            Long projectId,
            String message,
            List<String> knowledgeBaseIds,
            boolean stream,
            Map<String, String> aiConfig
    ) {
        this(
                userId,
                conversationId,
                turnId,
                projectId,
                message,
                knowledgeBaseIds,
                stream,
                aiConfig,
                List.of(),
                null,
                null,
                Map.of()
        );
    }
}
