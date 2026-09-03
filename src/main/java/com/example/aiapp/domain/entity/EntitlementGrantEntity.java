package com.example.aiapp.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户权益授予实体，对应 entitlement_grant 表。
 */
@Data
@TableName("entitlement_grant")
public class EntitlementGrantEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    @TableField("account_id")
    private Long accountId;
    @TableField("grant_type")
    private String grantType;
    @TableField("source_type")
    private String sourceType;
    @TableField("source_id")
    private String sourceId;
    @TableField("entitlement_code")
    private String entitlementCode;
    @TableField("price_version_code")
    private String priceVersionCode;
    @TableField("quantity_micros")
    private Long quantityMicros;
    @TableField("remaining_micros")
    private Long remainingMicros;
    @TableField("valid_from")
    private LocalDateTime validFrom;
    @TableField("expires_at")
    private LocalDateTime expiresAt;
    private String status;
    @TableField("metadata_json")
    private String metadataJson;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
