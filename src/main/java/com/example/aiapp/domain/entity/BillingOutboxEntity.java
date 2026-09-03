package com.example.aiapp.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 计费事件发件箱实体，对应 billing_outbox 表。
 */
@Data
@TableName("billing_outbox")
public class BillingOutboxEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    @TableField("aggregate_type")
    private String aggregateType;
    @TableField("aggregate_id")
    private String aggregateId;
    @TableField("event_type")
    private String eventType;
    @TableField("event_version")
    private Integer eventVersion;
    @TableField("payload_json")
    private String payloadJson;
    private String status;
    @TableField("retry_count")
    private Integer retryCount;
    @TableField("next_attempt_at")
    private LocalDateTime nextAttemptAt;
    @TableField("last_error")
    private String lastError;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("processed_at")
    private LocalDateTime processedAt;
}
