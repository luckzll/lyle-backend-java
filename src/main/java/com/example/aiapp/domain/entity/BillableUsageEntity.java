package com.example.aiapp.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

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

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsageEventId() { return usageEventId; }
    public void setUsageEventId(String usageEventId) { this.usageEventId = usageEventId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public String getPricingVersionCode() { return pricingVersionCode; }
    public void setPricingVersionCode(String pricingVersionCode) { this.pricingVersionCode = pricingVersionCode; }
    public Long getChargeCreditMicros() { return chargeCreditMicros; }
    public void setChargeCreditMicros(Long chargeCreditMicros) { this.chargeCreditMicros = chargeCreditMicros; }
    public String getUsageSource() { return usageSource; }
    public void setUsageSource(String usageSource) { this.usageSource = usageSource; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getReservationId() { return reservationId; }
    public void setReservationId(String reservationId) { this.reservationId = reservationId; }
    public Long getLedgerEntryId() { return ledgerEntryId; }
    public void setLedgerEntryId(Long ledgerEntryId) { this.ledgerEntryId = ledgerEntryId; }
    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getSettledAt() { return settledAt; }
    public void setSettledAt(LocalDateTime settledAt) { this.settledAt = settledAt; }
}
