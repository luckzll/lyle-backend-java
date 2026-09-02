package com.example.aiapp.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

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

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public LocalDate getReconciliationDate() { return reconciliationDate; }
    public void setReconciliationDate(LocalDate reconciliationDate) { this.reconciliationDate = reconciliationDate; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getPaymentNo() { return paymentNo; }
    public void setPaymentNo(String paymentNo) { this.paymentNo = paymentNo; }
    public String getProviderTransactionId() { return providerTransactionId; }
    public void setProviderTransactionId(String providerTransactionId) { this.providerTransactionId = providerTransactionId; }
    public Long getInternalAmountMinor() { return internalAmountMinor; }
    public void setInternalAmountMinor(Long internalAmountMinor) { this.internalAmountMinor = internalAmountMinor; }
    public Long getProviderAmountMinor() { return providerAmountMinor; }
    public void setProviderAmountMinor(Long providerAmountMinor) { this.providerAmountMinor = providerAmountMinor; }
    public String getInternalStatus() { return internalStatus; }
    public void setInternalStatus(String internalStatus) { this.internalStatus = internalStatus; }
    public String getProviderStatus() { return providerStatus; }
    public void setProviderStatus(String providerStatus) { this.providerStatus = providerStatus; }
    public String getDifferenceType() { return differenceType; }
    public void setDifferenceType(String differenceType) { this.differenceType = differenceType; }
    public String getProcessStatus() { return processStatus; }
    public void setProcessStatus(String processStatus) { this.processStatus = processStatus; }
    public Long getAssigneeUserId() { return assigneeUserId; }
    public void setAssigneeUserId(Long assigneeUserId) { this.assigneeUserId = assigneeUserId; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public LocalDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(LocalDateTime resolvedAt) { this.resolvedAt = resolvedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
