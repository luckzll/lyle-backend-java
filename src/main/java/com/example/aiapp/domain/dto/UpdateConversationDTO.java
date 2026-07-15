package com.example.aiapp.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateConversationDTO(
        @NotBlank @Size(max = 120) String title
) {
}
