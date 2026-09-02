package com.example.aiapp.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("billing_order")
public class BillingOrderEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    @TableField("order_no")
    private String orderNo;
    @TableField("user_id")
    private Long userId;
    @TableField("account_id")
    private Long accountId;
    @TableField("order_type")
    private String orderType;
    private String status;
    @TableField("product_code")
    private String productCode;
    @TableField("price_version_code")
    private String priceVersionCode;
    @TableField("product_snapshot")
    private String productSnapshot;
    private String currency;
    @TableField("original_amount_minor")
    private Long originalAmountMinor;
    @TableField("discount_amount_minor")
    private Long discountAmountMinor;
    @TableField("payable_amount_minor")
    private Long payableAmountMinor;
    @TableField("paid_amount_minor")
    private Long paidAmountMinor;
    @TableField("client_request_id")
    private String clientRequestId;
    @TableField("expires_at")
    private LocalDateTime expiresAt;
    @TableField("paid_at")
    private LocalDateTime paidAt;
    @TableField("fulfilled_at")
    private LocalDateTime fulfilledAt;
    @TableField("closed_at")
    private LocalDateTime closedAt;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("updated_at")
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public String getOrderType() { return orderType; }
    public void setOrderType(String orderType) { this.orderType = orderType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }
    public String getPriceVersionCode() { return priceVersionCode; }
    public void setPriceVersionCode(String priceVersionCode) { this.priceVersionCode = priceVersionCode; }
    public String getProductSnapshot() { return productSnapshot; }
    public void setProductSnapshot(String productSnapshot) { this.productSnapshot = productSnapshot; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public Long getOriginalAmountMinor() { return originalAmountMinor; }
    public void setOriginalAmountMinor(Long originalAmountMinor) { this.originalAmountMinor = originalAmountMinor; }
    public Long getDiscountAmountMinor() { return discountAmountMinor; }
    public void setDiscountAmountMinor(Long discountAmountMinor) { this.discountAmountMinor = discountAmountMinor; }
    public Long getPayableAmountMinor() { return payableAmountMinor; }
    public void setPayableAmountMinor(Long payableAmountMinor) { this.payableAmountMinor = payableAmountMinor; }
    public Long getPaidAmountMinor() { return paidAmountMinor; }
    public void setPaidAmountMinor(Long paidAmountMinor) { this.paidAmountMinor = paidAmountMinor; }
    public String getClientRequestId() { return clientRequestId; }
    public void setClientRequestId(String clientRequestId) { this.clientRequestId = clientRequestId; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public LocalDateTime getPaidAt() { return paidAt; }
    public void setPaidAt(LocalDateTime paidAt) { this.paidAt = paidAt; }
    public LocalDateTime getFulfilledAt() { return fulfilledAt; }
    public void setFulfilledAt(LocalDateTime fulfilledAt) { this.fulfilledAt = fulfilledAt; }
    public LocalDateTime getClosedAt() { return closedAt; }
    public void setClosedAt(LocalDateTime closedAt) { this.closedAt = closedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
