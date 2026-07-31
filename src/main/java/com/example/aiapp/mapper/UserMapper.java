package com.example.aiapp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.aiapp.domain.entity.UserEntity;

public interface UserMapper extends BaseMapper<UserEntity> {

    UserEntity selectByUsername(String username);

    default UserEntity selectAccountById(Long userId) {
        return selectById(userId);
    }
}
