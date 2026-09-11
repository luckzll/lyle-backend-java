package com.example.aiapp.service.impl;

import com.example.aiapp.domain.dto.AuthRequestDTO;
import com.example.aiapp.domain.dto.ChangePasswordRequestDTO;
import com.example.aiapp.domain.entity.UserEntity;
import com.example.aiapp.domain.vo.AuthResponseVO;
import com.example.aiapp.domain.vo.UserVO;
import com.example.aiapp.mapper.UserMapper;
import com.example.aiapp.security.AuthSessionService;
import com.example.aiapp.security.JwtService;
import com.example.aiapp.service.AuthService;
import com.example.aiapp.utils.PasswordUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/**
 * 用户身份认证服务实现。
 */
@Service
public class AuthServiceImpl implements AuthService {

    private static final String DEFAULT_ROLE = "USER";

    @Override
    @Transactional
    public AuthenticatedSession loginVerifiedUser(Long userId, String userAgent, String remoteAddress) {
        UserEntity entity = userMapper.selectAccountById(userId);
        if (entity == null || !"ACTIVE".equalsIgnoreCase(entity.getStatus())) {
            throw new ResponseStatusException(UNAUTHORIZED, "账号不可用，请联系管理员。");
        }
        entity.setLastLoginAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(entity);
        return createSession(entity, userAgent, remoteAddress);
    }

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthSessionService authSessionService;

    @Override
    @Transactional
    public AuthenticatedSession resetVerifiedPassword(Long userId, String password, String userAgent, String remoteAddress) {
        if (password == null || password.isBlank() || password.length() < 8 || password.length() > 64
                || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(BAD_REQUEST, "密码需要 8～64 个字符；较长的中文密码请适当缩短。");
        }
        UserEntity entity = userMapper.selectAccountById(userId);
        if (entity == null || !"ACTIVE".equalsIgnoreCase(entity.getStatus())) {
            throw new ResponseStatusException(UNAUTHORIZED, "账号不可用，请联系管理员。");
        }
        LocalDateTime now = LocalDateTime.now();
        entity.setPasswordHash(passwordEncoder.encode(password));
        entity.setAuthVersion(authVersion(entity) + 1);
        entity.setPasswordChangedAt(now);
        entity.setLastLoginAt(now);
        entity.setUpdatedAt(now);
        userMapper.updateById(entity);
        authSessionService.revokeAll(userId, "EMAIL_PASSWORD_RESET");
        return createSession(entity, userAgent, remoteAddress);
    }

    public AuthServiceImpl(
            UserMapper userMapper,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            AuthSessionService authSessionService
    ) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.authSessionService = authSessionService;
    }

    @Override
    @Transactional
    public AuthenticatedSession register(AuthRequestDTO request, String userAgent, String remoteAddress) {
        if (request.password().length() < 8) {
            throw new ResponseStatusException(BAD_REQUEST, "密码需要 8～128 个字符。");
        }
        if (userMapper.selectByUsername(request.username()) != null) {
            throw new ResponseStatusException(CONFLICT, "该用户名已被注册，请直接登录。");
        }

        LocalDateTime now = LocalDateTime.now();
        UserEntity entity = new UserEntity();
        entity.setUsername(request.username());
        entity.setRole(DEFAULT_ROLE);
        entity.setStatus("ACTIVE");
        entity.setAuthVersion(1);
        entity.setPasswordHash(passwordEncoder.encode(request.password()));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setLastLoginAt(now);
        userMapper.insert(entity);
        return createSession(entity, userAgent, remoteAddress);
    }

    @Override
    @Transactional
    public AuthenticatedSession login(AuthRequestDTO request, String userAgent, String remoteAddress) {
        UserEntity entity = userMapper.selectByUsername(request.username());
        if (entity == null
                || !"ACTIVE".equalsIgnoreCase(entity.getStatus())
                || !matches(request.password(), entity.getPasswordHash())) {
            throw new ResponseStatusException(UNAUTHORIZED, "用户名或密码不正确，请重试。");
        }
        LocalDateTime now = LocalDateTime.now();
        if (entity.getPasswordHash() != null && entity.getPasswordHash().startsWith("sha256:")) {
            entity.setPasswordHash(passwordEncoder.encode(request.password()));
        }
        entity.setLastLoginAt(now);
        entity.setUpdatedAt(now);
        userMapper.updateById(entity);
        return createSession(entity, userAgent, remoteAddress);
    }

    @Override
    public AuthenticatedSession refresh(String refreshToken) {
        AuthSessionService.RotationResult result = authSessionService.rotate(refreshToken);
        if (!result.succeeded()) {
            throw new ResponseStatusException(UNAUTHORIZED, "登录状态已失效，请重新登录。");
        }
        return fromGrant(result.grant());
    }

    @Override
    @Transactional
    public AuthResponseVO changePassword(Long userId, Long sessionId, ChangePasswordRequestDTO request) {
        UserEntity entity = userMapper.selectAccountById(userId);
        if (entity == null
                || !"ACTIVE".equalsIgnoreCase(entity.getStatus())
                || !matches(request.currentPassword(), entity.getPasswordHash())) {
            throw new ResponseStatusException(BAD_REQUEST, "当前密码不正确，请重试。");
        }
        if (request.currentPassword().equals(request.newPassword())) {
            throw new ResponseStatusException(BAD_REQUEST, "新密码不能与当前密码相同。");
        }
        LocalDateTime now = LocalDateTime.now();
        int nextAuthVersion = authVersion(entity) + 1;
        entity.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        entity.setAuthVersion(nextAuthVersion);
        entity.setPasswordChangedAt(now);
        entity.setUpdatedAt(now);
        userMapper.updateById(entity);
        authSessionService.keepCurrentAfterCredentialChange(userId, sessionId, nextAuthVersion);
        return response(entity, sessionId, nextAuthVersion);
    }

    @Override
    public void logout(Long userId, Long sessionId) {
        authSessionService.revokeCurrent(sessionId, userId, "USER_LOGOUT");
    }

    @Override
    public void logoutAll(Long userId) {
        authSessionService.revokeAll(userId, "USER_LOGOUT_ALL");
    }

    private AuthenticatedSession createSession(UserEntity entity, String userAgent, String remoteAddress) {
        AuthSessionService.SessionGrant grant = authSessionService.create(
                entity.getId(),
                authVersion(entity),
                entity.getUsername(),
                role(entity),
                status(entity),
                entity.getLastLoginAt(),
                userAgent,
                remoteAddress
        );
        return fromGrant(grant);
    }

    private AuthenticatedSession fromGrant(AuthSessionService.SessionGrant grant) {
        UserVO user = new UserVO(
                grant.userId(),
                grant.username(),
                grant.role(),
                grant.status(),
                grant.lastLoginAt()
        );
        AuthResponseVO response = new AuthResponseVO(
                user,
                jwtService.generateToken(
                        grant.userId(),
                        grant.username(),
                        grant.role(),
                        grant.sessionId(),
                        grant.authVersion()
                ),
                jwtService.expiresInSeconds()
        );
        return new AuthenticatedSession(response, grant.refreshToken());
    }

    private AuthResponseVO response(UserEntity entity, Long sessionId, int authVersion) {
        return new AuthResponseVO(
                new UserVO(
                        entity.getId(),
                        entity.getUsername(),
                        role(entity),
                        status(entity),
                        entity.getLastLoginAt()
                ),
                jwtService.generateToken(
                        entity.getId(),
                        entity.getUsername(),
                        role(entity),
                        sessionId,
                        authVersion
                ),
                jwtService.expiresInSeconds()
        );
    }

    private String role(UserEntity entity) {
        return entity.getRole() == null || entity.getRole().isBlank() ? DEFAULT_ROLE : entity.getRole();
    }

    private String status(UserEntity entity) {
        return entity.getStatus() == null || entity.getStatus().isBlank() ? "ACTIVE" : entity.getStatus();
    }

    private int authVersion(UserEntity entity) {
        return entity.getAuthVersion() == null ? 1 : entity.getAuthVersion();
    }

    private boolean matches(String password, String passwordHash) {
        if (passwordHash != null && passwordHash.startsWith("sha256:")) {
            return PasswordUtils.matches(password, passwordHash);
        }
        return passwordEncoder.matches(password, passwordHash);
    }
}
