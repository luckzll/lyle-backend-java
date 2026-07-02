package com.example.aiapp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.aiapp.domain.entity.ConversationEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ConversationMapper extends BaseMapper<ConversationEntity> {

    List<ConversationEntity> selectByUserId(Long userId);

    ConversationEntity selectByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);
}
