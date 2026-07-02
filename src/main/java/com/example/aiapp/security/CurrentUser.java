package com.example.aiapp.security;

public record CurrentUser(
        Long id,
        String username,
        String role
) {
}
