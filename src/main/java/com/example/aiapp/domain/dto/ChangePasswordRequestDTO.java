package com.example.aiapp.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequestDTO(
        @NotBlank
        String currentPassword,

        @NotBlank
        @Size(min = 8, max = 128)
        String newPassword
) {
}
