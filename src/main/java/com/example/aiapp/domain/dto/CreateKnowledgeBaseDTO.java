package com.example.aiapp.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateKnowledgeBaseDTO(
        @NotBlank @Size(max = 255) String name
) {
}
