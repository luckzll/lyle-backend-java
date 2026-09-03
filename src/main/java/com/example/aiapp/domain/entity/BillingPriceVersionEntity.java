package com.example.aiapp.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商品价格版本实体，对应 billing_price_version 表。
 */
@Data
@TableName("billing_price_version")
public class BillingPriceVersionEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("product_id")
    private Long productId;

    @TableField("version_code")
    private String versionCode;

    private String currency;

    @TableField("amount_minor")
    private Long amountMinor;

    @TableField("included_credit_micros")
    private Long includedCreditMicros;

    @TableField("validity_days")
    private Integer validityDays;

    @TableField("entitlement_snapshot")
    private String entitlementSnapshot;

    @TableField("pricing_rule_snapshot")
    private String pricingRuleSnapshot;

    private String status;

    @TableField("sell_from")
    private LocalDateTime sellFrom;

    @TableField("sell_until")
    private LocalDateTime sellUntil;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("published_at")
    private LocalDateTime publishedAt;
}
