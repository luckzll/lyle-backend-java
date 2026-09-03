package com.example.aiapp.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计日志实体，对应 audit_log 表。
 */
@Data
@TableName("audit_log")
public class AuditLogEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("user_id")
    private Long userId;

    private String action;

    private String target;

    @TableField("target_id")
    private String targetId;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
