package com.example.aiapp.domain.vo;

public record AuthResponseVO(
        UserVO user,
        String accessToken,
        long expiresInSeconds
) {
    public AuthResponseVO(UserVO user, String accessToken) {
        this(user, accessToken, 900);
    }
}
