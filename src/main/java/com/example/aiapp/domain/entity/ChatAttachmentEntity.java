package com.example.aiapp.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对话附件实体，对应 chat_attachment 表。
 */
@Data
@TableName("chat_attachment")
public class ChatAttachmentEntity {

    @TableId(type = IdType.INPUT)
    private String id;
    @TableField("user_id")
    private Long userId;
    @TableField("conversation_id")
    private Long conversationId;
    @TableField("message_id")
    private Long messageId;
    private String kind;
    private String status;
    @TableField("original_name")
    private String originalName;
    @TableField("content_type")
    private String contentType;
    @TableField("file_size")
    private Long fileSize;
    @TableField("storage_provider")
    private String storageProvider;
    @TableField("storage_path")
    private String storagePath;
    private String bucket;
    @TableField("object_key")
    private String objectKey;
    private Integer width;
    private Integer height;
    private Integer position;
    @TableField("source_type")
    private String sourceType;
    @TableField("folder_group_id")
    private String folderGroupId;
    @TableField("folder_root")
    private String folderRoot;
    @TableField("relative_path")
    private String relativePath;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("attached_at")
    private LocalDateTime attachedAt;
    @TableField("expires_at")
    private LocalDateTime expiresAt;
}
