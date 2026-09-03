package com.example.aiapp.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商业账户流水实体，对应 commercial_ledger_entry 表。
 */
@Data
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
}
