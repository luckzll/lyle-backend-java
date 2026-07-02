package com.example.aiapp.domain.dto;

import jakarta.validation.constraints.NotBlank;

public record ToolStatusUpdateDTO(@NotBlank String status) {
}
