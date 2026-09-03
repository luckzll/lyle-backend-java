package com.example.aiapp.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 支付对账差异项实体，对应 payment_reconciliation_item 表。
 */
@Data
@TableName("payment_reconciliation_item")
public class PaymentReconciliationItemEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    @TableField("reconciliation_date")
    private LocalDate reconciliationDate;
    private String provider;
    @TableField("payment_no")
    private String paymentNo;
    @TableField("provider_transaction_id")
    private String providerTransactionId;
    @TableField("internal_amount_minor")
    private Long internalAmountMinor;
    @TableField("provider_amount_minor")
    private Long providerAmountMinor;
    @TableField("internal_status")
    private String internalStatus;
    @TableField("provider_status")
    private String providerStatus;
    @TableField("difference_type")
    private String differenceType;
    @TableField("process_status")
    private String processStatus;
    @TableField("assignee_user_id")
    private Long assigneeUserId;
    private String note;
    @TableField("resolved_at")
    private LocalDateTime resolvedAt;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
