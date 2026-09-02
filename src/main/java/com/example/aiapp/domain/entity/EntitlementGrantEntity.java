package com.example.aiapp.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

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

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public String getGrantType() { return grantType; }
    public void setGrantType(String grantType) { this.grantType = grantType; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }
    public String getEntitlementCode() { return entitlementCode; }
    public void setEntitlementCode(String entitlementCode) { this.entitlementCode = entitlementCode; }
    public String getPriceVersionCode() { return priceVersionCode; }
    public void setPriceVersionCode(String priceVersionCode) { this.priceVersionCode = priceVersionCode; }
    public Long getQuantityMicros() { return quantityMicros; }
    public void setQuantityMicros(Long quantityMicros) { this.quantityMicros = quantityMicros; }
    public Long getRemainingMicros() { return remainingMicros; }
    public void setRemainingMicros(Long remainingMicros) { this.remainingMicros = remainingMicros; }
    public LocalDateTime getValidFrom() { return validFrom; }
    public void setValidFrom(LocalDateTime validFrom) { this.validFrom = validFrom; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
