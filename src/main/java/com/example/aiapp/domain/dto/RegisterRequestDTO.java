package com.example.aiapp.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequestDTO(
        @NotBlank
        @Size(min = 3, max = 64)
        String username,

        @NotBlank
        @Size(min = 8, max = 128)
        String password
) {
    public AuthRequestDTO toAuthRequest() {
        return new AuthRequestDTO(username, password);
    }
}
