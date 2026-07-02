package com.example.aiapp.controller;

import com.example.aiapp.domain.vo.UserVO;
import com.example.aiapp.security.CurrentUser;
import com.example.aiapp.utils.CurrentUserUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
public class UserController {

    @GetMapping("/me")
    UserVO me() {
        CurrentUser currentUser = CurrentUserUtils.currentUser();
        return new UserVO(currentUser.id(), currentUser.username(), currentUser.role());
    }
}
