package com.example.aiapp.domain.dto;

import jakarta.validation.constraints.NotBlank;

public record AiConfigUpdateDTO(
        @NotBlank String key,
        @NotBlank String value
) {
}
