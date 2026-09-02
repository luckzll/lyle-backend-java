package com.example.aiapp.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("billing_price_version")
public class BillingPriceVersionEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("product_id")
    private Long productId;

    @TableField("version_code")
    private String versionCode;

    private String currency;

    @TableField("amount_minor")
    private Long amountMinor;

    @TableField("included_credit_micros")
    private Long includedCreditMicros;

    @TableField("validity_days")
    private Integer validityDays;

    @TableField("entitlement_snapshot")
    private String entitlementSnapshot;

    @TableField("pricing_rule_snapshot")
    private String pricingRuleSnapshot;

    private String status;

    @TableField("sell_from")
    private LocalDateTime sellFrom;

    @TableField("sell_until")
    private LocalDateTime sellUntil;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("published_at")
    private LocalDateTime publishedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public String getVersionCode() {
        return versionCode;
    }

    public void setVersionCode(String versionCode) {
        this.versionCode = versionCode;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public Long getAmountMinor() {
        return amountMinor;
    }

    public void setAmountMinor(Long amountMinor) {
        this.amountMinor = amountMinor;
    }

    public Long getIncludedCreditMicros() {
        return includedCreditMicros;
    }

    public void setIncludedCreditMicros(Long includedCreditMicros) {
        this.includedCreditMicros = includedCreditMicros;
    }

    public Integer getValidityDays() {
        return validityDays;
    }

    public void setValidityDays(Integer validityDays) {
        this.validityDays = validityDays;
    }

    public String getEntitlementSnapshot() {
        return entitlementSnapshot;
    }

    public void setEntitlementSnapshot(String entitlementSnapshot) {
        this.entitlementSnapshot = entitlementSnapshot;
    }

    public String getPricingRuleSnapshot() {
        return pricingRuleSnapshot;
    }

    public void setPricingRuleSnapshot(String pricingRuleSnapshot) {
        this.pricingRuleSnapshot = pricingRuleSnapshot;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getSellFrom() {
        return sellFrom;
    }

    public void setSellFrom(LocalDateTime sellFrom) {
        this.sellFrom = sellFrom;
    }

    public LocalDateTime getSellUntil() {
        return sellUntil;
    }

    public void setSellUntil(LocalDateTime sellUntil) {
        this.sellUntil = sellUntil;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(LocalDateTime publishedAt) {
        this.publishedAt = publishedAt;
    }
}
