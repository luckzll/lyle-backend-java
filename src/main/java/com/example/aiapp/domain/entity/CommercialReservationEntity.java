package com.example.aiapp.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商业额度预留实体，对应 commercial_reservation 表。
 */
@Data
@TableName("commercial_reservation")
public class CommercialReservationEntity {
    @TableId
    private String id;
    @TableField("account_id")
    private Long accountId;
    @TableField("user_id")
    private Long userId;
    @TableField("turn_id")
    private String turnId;
    @TableField("reserved_credit_micros")
    private Long reservedCreditMicros;
    @TableField("consumed_credit_micros")
    private Long consumedCreditMicros;
    private String status;
    @TableField("expires_at")
    private LocalDateTime expiresAt;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
