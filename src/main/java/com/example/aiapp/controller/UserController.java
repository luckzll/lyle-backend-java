package com.example.aiapp.controller;

import com.example.aiapp.domain.vo.UserVO;
import com.example.aiapp.domain.entity.UserEntity;
import com.example.aiapp.mapper.UserMapper;
import com.example.aiapp.security.CurrentUser;
import com.example.aiapp.utils.CurrentUserUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
public class UserController {

    private final UserMapper userMapper;

    public UserController(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @GetMapping("/me")
    UserVO me() {
        CurrentUser currentUser = CurrentUserUtils.currentUser();
        UserEntity entity = userMapper.selectAccountById(currentUser.id());
        if (entity == null || !"ACTIVE".equalsIgnoreCase(entity.getStatus())) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED);
        }
        return new UserVO(
                entity.getId(),
                entity.getUsername(),
                entity.getRole(),
                entity.getStatus(),
                entity.getLastLoginAt()
        );
    }
}
