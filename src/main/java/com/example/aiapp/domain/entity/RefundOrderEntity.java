package com.example.aiapp.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 退款单实体，对应 refund_order 表。
 */
@Data
@TableName("refund_order")
public class RefundOrderEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    @TableField("refund_no")
    private String refundNo;
    @TableField("request_id")
    private String requestId;
    @TableField("order_id")
    private Long orderId;
    @TableField("payment_attempt_id")
    private Long paymentAttemptId;
    @TableField("user_id")
    private Long userId;
    private String status;
    private String currency;
    @TableField("amount_minor")
    private Long amountMinor;
    @TableField("reason_code")
    private String reasonCode;
    @TableField("reason_note")
    private String reasonNote;
    @TableField("provider_refund_id")
    private String providerRefundId;
    @TableField("requested_by")
    private Long requestedBy;
    @TableField("succeeded_at")
    private LocalDateTime succeededAt;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
