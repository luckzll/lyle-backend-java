package com.example.aiapp.domain.vo;

public record UserVO(
        Long id,
        String username,
        String role,
        String status,
        java.time.LocalDateTime lastLoginAt
) {
    public UserVO(Long id, String username, String role) {
        this(id, username, role, "ACTIVE", null);
    }
}
