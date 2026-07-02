package com.example.aiapp.domain.vo;

public record AuthResponseVO(
        UserVO user,
        String token
) {
}
