package com.example.aiapp.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("commercial_ledger_entry")
public class CommercialLedgerEntryEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    @TableField("account_id")
    private Long accountId;
    @TableField("grant_id")
    private Long grantId;
    @TableField("entry_type")
    private String entryType;
    private String direction;
    @TableField("credit_micros")
    private Long creditMicros;
    @TableField("balance_after_micros")
    private Long balanceAfterMicros;
    @TableField("source_type")
    private String sourceType;
    @TableField("source_id")
    private String sourceId;
    @TableField("idempotency_key")
    private String idempotencyKey;
    @TableField("occurred_at")
    private LocalDateTime occurredAt;
    @TableField("expires_at")
    private LocalDateTime expiresAt;
    @TableField("metadata_json")
    private String metadataJson;
    @TableField("created_at")
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public Long getGrantId() { return grantId; }
    public void setGrantId(Long grantId) { this.grantId = grantId; }
    public String getEntryType() { return entryType; }
    public void setEntryType(String entryType) { this.entryType = entryType; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public Long getCreditMicros() { return creditMicros; }
    public void setCreditMicros(Long creditMicros) { this.creditMicros = creditMicros; }
    public Long getBalanceAfterMicros() { return balanceAfterMicros; }
    public void setBalanceAfterMicros(Long balanceAfterMicros) { this.balanceAfterMicros = balanceAfterMicros; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime occurredAt) { this.occurredAt = occurredAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
