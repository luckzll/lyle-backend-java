package com.example.aiapp.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 可计费用量实体，对应 billable_usage 表。
 */
@Data
@TableName("billable_usage")
public class BillableUsageEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    @TableField("usage_event_id")
    private String usageEventId;
    @TableField("user_id")
    private Long userId;
    @TableField("account_id")
    private Long accountId;
    @TableField("pricing_version_code")
    private String pricingVersionCode;
    @TableField("charge_credit_micros")
    private Long chargeCreditMicros;
    @TableField("usage_source")
    private String usageSource;
    private String status;
    @TableField("reservation_id")
    private String reservationId;
    @TableField("ledger_entry_id")
    private Long ledgerEntryId;
    @TableField("metadata_json")
    private String metadataJson;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("settled_at")
    private LocalDateTime settledAt;
}
