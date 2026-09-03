package com.example.aiapp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.aiapp.domain.entity.ConversationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户会话数据访问接口。
 */
@Mapper
public interface ConversationMapper extends BaseMapper<ConversationEntity> {

    /** 按更新时间倒序查询用户的全部会话。 */
    List<ConversationEntity> selectByUserId(Long userId);

    /** 按会话主键和用户主键查询归属记录。 */
    ConversationEntity selectByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /** 分页查询用户会话。 */
    IPage<ConversationEntity> selectPageByUserId(
            Page<ConversationEntity> page,
            @Param("userId") Long userId
    );
}
