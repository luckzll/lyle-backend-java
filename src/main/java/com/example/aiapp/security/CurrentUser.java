package com.example.aiapp.security;

public record CurrentUser(
        Long id,
        String username,
        String role,
        Long sessionId,
        int authVersion
) {
    public CurrentUser(Long id, String username, String role) {
        this(id, username, role, 0L, 1);
    }
}
