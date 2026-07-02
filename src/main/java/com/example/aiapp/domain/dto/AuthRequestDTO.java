package com.example.aiapp.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AuthRequestDTO(
        @NotBlank
        @Size(min = 3, max = 64)
        String username,

        @NotBlank
        @Size(min = 6, max = 128)
        String password
) {
}
