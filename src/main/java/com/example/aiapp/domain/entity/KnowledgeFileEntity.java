package com.example.aiapp.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库文件实体，对应 knowledge_file 表。
 */
@Data
@TableName("knowledge_file")
public class KnowledgeFileEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("knowledge_base_id")
    private Long knowledgeBaseId;

    @TableField("file_name")
    private String fileName;

    @TableField("storage_path")
    private String storagePath;

    @TableField("storage_provider")
    private String storageProvider;

    @TableField("bucket")
    private String bucket;

    @TableField("object_key")
    private String objectKey;

    @TableField("original_name")
    private String originalName;

    @TableField("content_type")
    private String contentType;

    @TableField("file_size")
    private Long fileSize;

    @TableField("visibility")
    private String visibility;

    @TableField("parse_status")
    private String parseStatus;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
