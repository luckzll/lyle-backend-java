package com.example.aiapp.service;

import com.example.aiapp.domain.dto.AuthRequestDTO;
import com.example.aiapp.domain.vo.AuthResponseVO;

public interface AuthService {

    AuthResponseVO register(AuthRequestDTO request);

    AuthResponseVO login(AuthRequestDTO request);
}
