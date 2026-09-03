package com.example.aiapp.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用量计价规则版本实体，对应 billing_usage_pricing_version 表。
 */
@Data
@TableName("billing_usage_pricing_version")
public class BillingUsagePricingVersionEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    @TableField("version_code")
    private String versionCode;
    @TableField("rules_json")
    private String rulesJson;
    private String status;
    @TableField("effective_from")
    private LocalDateTime effectiveFrom;
    @TableField("effective_until")
    private LocalDateTime effectiveUntil;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("published_at")
    private LocalDateTime publishedAt;
}
