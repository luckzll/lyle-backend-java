package com.example.aiapp.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("refund_order")
public class RefundOrderEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    @TableField("refund_no")
    private String refundNo;
    @TableField("request_id")
    private String requestId;
    @TableField("order_id")
    private Long orderId;
    @TableField("payment_attempt_id")
    private Long paymentAttemptId;
    @TableField("user_id")
    private Long userId;
    private String status;
    private String currency;
    @TableField("amount_minor")
    private Long amountMinor;
    @TableField("reason_code")
    private String reasonCode;
    @TableField("reason_note")
    private String reasonNote;
    @TableField("provider_refund_id")
    private String providerRefundId;
    @TableField("requested_by")
    private Long requestedBy;
    @TableField("succeeded_at")
    private LocalDateTime succeededAt;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("updated_at")
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRefundNo() { return refundNo; }
    public void setRefundNo(String refundNo) { this.refundNo = refundNo; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public Long getPaymentAttemptId() { return paymentAttemptId; }
    public void setPaymentAttemptId(Long paymentAttemptId) { this.paymentAttemptId = paymentAttemptId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public Long getAmountMinor() { return amountMinor; }
    public void setAmountMinor(Long amountMinor) { this.amountMinor = amountMinor; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    public String getReasonNote() { return reasonNote; }
    public void setReasonNote(String reasonNote) { this.reasonNote = reasonNote; }
    public String getProviderRefundId() { return providerRefundId; }
    public void setProviderRefundId(String providerRefundId) { this.providerRefundId = providerRefundId; }
    public Long getRequestedBy() { return requestedBy; }
    public void setRequestedBy(Long requestedBy) { this.requestedBy = requestedBy; }
    public LocalDateTime getSucceededAt() { return succeededAt; }
    public void setSucceededAt(LocalDateTime succeededAt) { this.succeededAt = succeededAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
