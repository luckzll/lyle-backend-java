package com.example.aiapp.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 支付回调事件实体，对应 payment_webhook_event 表。
 */
@Data
@TableName("payment_webhook_event")
public class PaymentWebhookEventEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String provider;
    @TableField("provider_event_id")
    private String providerEventId;
    @TableField("payload_hash")
    private String payloadHash;
    @TableField("signature_valid")
    private Boolean signatureValid;
    @TableField("process_status")
    private String processStatus;
    @TableField("payment_no")
    private String paymentNo;
    @TableField("received_at")
    private LocalDateTime receivedAt;
    @TableField("processed_at")
    private LocalDateTime processedAt;
    @TableField("error_code")
    private String errorCode;
}
