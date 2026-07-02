package com.example.aiapp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.aiapp.domain.entity.KnowledgeBaseEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBaseEntity> {

    List<KnowledgeBaseEntity> selectByUserId(Long userId);

    KnowledgeBaseEntity selectByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);
}
