package com.example.aiapp.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("payment_webhook_event")
public class PaymentWebhookEventEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String provider;
    @TableField("provider_event_id")
    private String providerEventId;
    @TableField("payload_hash")
    private String payloadHash;
    @TableField("signature_valid")
    private Boolean signatureValid;
    @TableField("process_status")
    private String processStatus;
    @TableField("payment_no")
    private String paymentNo;
    @TableField("received_at")
    private LocalDateTime receivedAt;
    @TableField("processed_at")
    private LocalDateTime processedAt;
    @TableField("error_code")
    private String errorCode;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getProviderEventId() { return providerEventId; }
    public void setProviderEventId(String providerEventId) { this.providerEventId = providerEventId; }
    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String payloadHash) { this.payloadHash = payloadHash; }
    public Boolean getSignatureValid() { return signatureValid; }
    public void setSignatureValid(Boolean signatureValid) { this.signatureValid = signatureValid; }
    public String getProcessStatus() { return processStatus; }
    public void setProcessStatus(String processStatus) { this.processStatus = processStatus; }
    public String getPaymentNo() { return paymentNo; }
    public void setPaymentNo(String paymentNo) { this.paymentNo = paymentNo; }
    public LocalDateTime getReceivedAt() { return receivedAt; }
    public void setReceivedAt(LocalDateTime receivedAt) { this.receivedAt = receivedAt; }
    public LocalDateTime getProcessedAt() { return processedAt; }
    public void setProcessedAt(LocalDateTime processedAt) { this.processedAt = processedAt; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
}
