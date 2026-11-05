package com.example.aiapp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.aiapp.domain.entity.KnowledgeFileEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 知识库文件数据访问接口。
 */
@Mapper
public interface KnowledgeFileMapper extends BaseMapper<KnowledgeFileEntity> {
    @Select("SELECT id FROM knowledge_file WHERE id = #{id} AND knowledge_base_id = #{knowledgeBaseId} FOR UPDATE")
    Long lockAdminRetry(@Param("id") Long id, @Param("knowledgeBaseId") Long knowledgeBaseId);

    /** 查询指定知识库的全部文件。 */
    List<KnowledgeFileEntity> selectByKnowledgeBaseId(Long knowledgeBaseId);

    /** 按文件主键和知识库主键查询归属记录。 */
    KnowledgeFileEntity selectByIdAndKnowledgeBaseId(@Param("id") Long id, @Param("knowledgeBaseId") Long knowledgeBaseId);

    /** 删除指定知识库的全部文件记录。 */
    int deleteByKnowledgeBaseId(@Param("knowledgeBaseId") Long knowledgeBaseId);

    int updateParseStatusIfRevision(@Param("id") Long id, @Param("knowledgeBaseId") Long knowledgeBaseId,
                                   @Param("revision") String revision, @Param("status") String status);

    /** 分页查询指定知识库的文件。 */
    IPage<KnowledgeFileEntity> selectPageByKnowledgeBaseId(
            Page<KnowledgeFileEntity> page,
            @Param("knowledgeBaseId") Long knowledgeBaseId
    );
}
