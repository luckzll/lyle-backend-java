package com.example.aiapp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.aiapp.domain.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 系统用户数据访问接口。
 */
@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {

    /** 按用户名查询账号。 */
    UserEntity selectByUsername(String username);

    /** 按用户主键查询账号。 */
    default UserEntity selectAccountById(Long userId) {
        return selectById(userId);
    }
}
