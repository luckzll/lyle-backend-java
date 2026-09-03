package com.example.aiapp.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 支付尝试实体，对应 payment_attempt 表。
 */
@Data
@TableName("payment_attempt")
public class PaymentAttemptEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    @TableField("payment_no")
    private String paymentNo;
    @TableField("order_id")
    private Long orderId;
    private String provider;
    private String status;
    private String currency;
    @TableField("amount_minor")
    private Long amountMinor;
    @TableField("provider_transaction_id")
    private String providerTransactionId;
    @TableField("checkout_payload_encrypted")
    private String checkoutPayloadEncrypted;
    @TableField("expires_at")
    private LocalDateTime expiresAt;
    @TableField("succeeded_at")
    private LocalDateTime succeededAt;
    @TableField("closed_at")
    private LocalDateTime closedAt;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
