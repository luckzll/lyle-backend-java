package com.example.aiapp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.aiapp.domain.entity.KnowledgeFileEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface KnowledgeFileMapper extends BaseMapper<KnowledgeFileEntity> {

    List<KnowledgeFileEntity> selectByKnowledgeBaseId(Long knowledgeBaseId);

    KnowledgeFileEntity selectByIdAndKnowledgeBaseId(@Param("id") Long id, @Param("knowledgeBaseId") Long knowledgeBaseId);

    int deleteByKnowledgeBaseId(@Param("knowledgeBaseId") Long knowledgeBaseId);
}
