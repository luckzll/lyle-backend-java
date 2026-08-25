package com.example.aiapp.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequestDTO(
        @NotBlank(message = "请输入用户名。")
        @Size(min = 3, max = 64, message = "用户名需要 3～64 个字符。")
        String username,

        @NotBlank(message = "请输入密码。")
        @Size(min = 8, max = 128, message = "密码需要 8～128 个字符。")
        String password
) {
    public AuthRequestDTO toAuthRequest() {
        return new AuthRequestDTO(username, password);
    }
}
