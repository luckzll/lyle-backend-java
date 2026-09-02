package com.example.aiapp.domain.vo;

import java.time.LocalDateTime;

public class BillingOrderReadVO {
    private String orderNo;
    private String status;
    private String orderType;
    private String productCode;
    private String priceVersionCode;
    private String productSnapshot;
    private String currency;
    private Long payableAmountMinor;
    private Long paidAmountMinor;
    private String paymentNo;
    private String paymentStatus;
    private String paymentProvider;
    private String refundStatus;
    private LocalDateTime refundSucceededAt;
    private LocalDateTime expiresAt;
    private LocalDateTime paidAt;
    private LocalDateTime fulfilledAt;
    private LocalDateTime createdAt;

    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getOrderType() { return orderType; }
    public void setOrderType(String orderType) { this.orderType = orderType; }
    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }
    public String getPriceVersionCode() { return priceVersionCode; }
    public void setPriceVersionCode(String priceVersionCode) { this.priceVersionCode = priceVersionCode; }
    public String getProductSnapshot() { return productSnapshot; }
    public void setProductSnapshot(String productSnapshot) { this.productSnapshot = productSnapshot; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public Long getPayableAmountMinor() { return payableAmountMinor; }
    public void setPayableAmountMinor(Long payableAmountMinor) { this.payableAmountMinor = payableAmountMinor; }
    public Long getPaidAmountMinor() { return paidAmountMinor; }
    public void setPaidAmountMinor(Long paidAmountMinor) { this.paidAmountMinor = paidAmountMinor; }
    public String getPaymentNo() { return paymentNo; }
    public void setPaymentNo(String paymentNo) { this.paymentNo = paymentNo; }
    public String getPaymentStatus() { return paymentStatus; }
    public void setPaymentStatus(String paymentStatus) { this.paymentStatus = paymentStatus; }
    public String getPaymentProvider() { return paymentProvider; }
    public void setPaymentProvider(String paymentProvider) { this.paymentProvider = paymentProvider; }
    public String getRefundStatus() { return refundStatus; }
    public void setRefundStatus(String refundStatus) { this.refundStatus = refundStatus; }
    public LocalDateTime getRefundSucceededAt() { return refundSucceededAt; }
    public void setRefundSucceededAt(LocalDateTime refundSucceededAt) { this.refundSucceededAt = refundSucceededAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public LocalDateTime getPaidAt() { return paidAt; }
    public void setPaidAt(LocalDateTime paidAt) { this.paidAt = paidAt; }
    public LocalDateTime getFulfilledAt() { return fulfilledAt; }
    public void setFulfilledAt(LocalDateTime fulfilledAt) { this.fulfilledAt = fulfilledAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
