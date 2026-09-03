package com.example.aiapp.service;

import com.example.aiapp.domain.dto.AuthRequestDTO;
import com.example.aiapp.domain.dto.ChangePasswordRequestDTO;
import com.example.aiapp.domain.vo.AuthResponseVO;

/**
 * 用户身份认证服务。
 */
public interface AuthService {

    AuthenticatedSession register(AuthRequestDTO request, String userAgent, String remoteAddress);

    default AuthResponseVO register(AuthRequestDTO request) {
        return register(request, null, null).response();
    }

    AuthenticatedSession login(AuthRequestDTO request, String userAgent, String remoteAddress);

    default AuthResponseVO login(AuthRequestDTO request) {
        return login(request, null, null).response();
    }

    AuthenticatedSession refresh(String refreshToken);

    AuthResponseVO changePassword(Long userId, Long sessionId, ChangePasswordRequestDTO request);

    void logout(Long userId, Long sessionId);

    void logoutAll(Long userId);

    record AuthenticatedSession(AuthResponseVO response, String refreshToken) {
    }
}
