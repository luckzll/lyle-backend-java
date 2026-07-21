package com.example.aiapp.domain.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record ChatRequestDTO(
        String conversationId,
        String turnId,
        Long projectId,
        @NotBlank String message,
        List<String> knowledgeBaseIds
) {
}
