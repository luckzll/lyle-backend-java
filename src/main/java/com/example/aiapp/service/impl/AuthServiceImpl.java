package com.example.aiapp.service.impl;

import com.example.aiapp.domain.dto.AuthRequestDTO;
import com.example.aiapp.domain.entity.UserEntity;
import com.example.aiapp.domain.vo.AuthResponseVO;
import com.example.aiapp.domain.vo.UserVO;
import com.example.aiapp.mapper.UserMapper;
import com.example.aiapp.security.JwtService;
import com.example.aiapp.service.AuthService;
import com.example.aiapp.utils.PasswordUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Service
public class AuthServiceImpl implements AuthService {

    private static final String DEFAULT_ROLE = "USER";

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthServiceImpl(UserMapper userMapper, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Override
    public AuthResponseVO register(AuthRequestDTO request) {
        if (userMapper.selectByUsername(request.username()) != null) {
            throw new ResponseStatusException(CONFLICT, "用户名或密码不正确，请重试。");
        }

        UserEntity entity = new UserEntity();
        entity.setUsername(request.username());
        entity.setRole(DEFAULT_ROLE);
        entity.setPasswordHash(passwordEncoder.encode(request.password()));
        entity.setCreatedAt(LocalDateTime.now());
        userMapper.insert(entity);
        return toResponse(entity);
    }

    @Override
    public AuthResponseVO login(AuthRequestDTO request) {
        UserEntity entity = userMapper.selectByUsername(request.username());
        if (entity == null || !matches(request.password(), entity.getPasswordHash())) {
            throw new ResponseStatusException(UNAUTHORIZED, "用户名或密码不正确，请重试。");
        }
        return toResponse(entity);
    }

    private AuthResponseVO toResponse(UserEntity entity) {
        return new AuthResponseVO(
                new UserVO(entity.getId(), entity.getUsername(), role(entity)),
                jwtService.generateToken(entity.getId(), entity.getUsername(), role(entity))
        );
    }

    private String role(UserEntity entity) {
        return entity.getRole() == null || entity.getRole().isBlank() ? DEFAULT_ROLE : entity.getRole();
    }

    private boolean matches(String password, String passwordHash) {
        if (passwordHash != null && passwordHash.startsWith("sha256:")) {
            return PasswordUtils.matches(password, passwordHash);
        }
        return passwordEncoder.matches(password, passwordHash);
    }
}
