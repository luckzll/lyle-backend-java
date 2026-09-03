package com.example.aiapp.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 计费订单实体，对应 billing_order 表。
 */
@Data
@TableName("billing_order")
public class BillingOrderEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    @TableField("order_no")
    private String orderNo;
    @TableField("user_id")
    private Long userId;
    @TableField("account_id")
    private Long accountId;
    @TableField("order_type")
    private String orderType;
    private String status;
    @TableField("product_code")
    private String productCode;
    @TableField("price_version_code")
    private String priceVersionCode;
    @TableField("product_snapshot")
    private String productSnapshot;
    private String currency;
    @TableField("original_amount_minor")
    private Long originalAmountMinor;
    @TableField("discount_amount_minor")
    private Long discountAmountMinor;
    @TableField("payable_amount_minor")
    private Long payableAmountMinor;
    @TableField("paid_amount_minor")
    private Long paidAmountMinor;
    @TableField("client_request_id")
    private String clientRequestId;
    @TableField("expires_at")
    private LocalDateTime expiresAt;
    @TableField("paid_at")
    private LocalDateTime paidAt;
    @TableField("fulfilled_at")
    private LocalDateTime fulfilledAt;
    @TableField("closed_at")
    private LocalDateTime closedAt;
    @TableField("user_hidden_at")
    private LocalDateTime userHiddenAt;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
