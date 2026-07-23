package com.example.aiapp.controller;

import com.example.aiapp.domain.dto.AuthRequestDTO;
import com.example.aiapp.domain.vo.AuthResponseVO;
import com.example.aiapp.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    AuthResponseVO register(@Valid @RequestBody AuthRequestDTO request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    AuthResponseVO login(@Valid @RequestBody AuthRequestDTO request) {
        return authService.login(request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> invalidCredentials() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("detail", "用户名或密码不正确，请重试。"));
    }
}
