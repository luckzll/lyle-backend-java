package com.example.aiapp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.aiapp.domain.entity.KnowledgeBaseEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户知识库数据访问接口。
 */
@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBaseEntity> {

    /** 查询用户的全部知识库。 */
    List<KnowledgeBaseEntity> selectByUserId(Long userId);

    /** 按知识库主键和用户主键查询归属记录。 */
    KnowledgeBaseEntity selectByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /** 分页查询用户知识库。 */
    IPage<KnowledgeBaseEntity> selectPageByUserId(
            Page<KnowledgeBaseEntity> page,
            @Param("userId") Long userId
    );
}
