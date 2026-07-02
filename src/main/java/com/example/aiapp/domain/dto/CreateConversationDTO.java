package com.example.aiapp.domain.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateConversationDTO(
        @NotBlank String title
) {
}
