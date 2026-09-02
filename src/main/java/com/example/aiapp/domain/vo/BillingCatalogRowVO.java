package com.example.aiapp.domain.vo;

import java.time.LocalDateTime;

public class BillingCatalogRowVO {

    private String productCode;
    private String productType;
    private String displayName;
    private String description;
    private String versionCode;
    private String currency;
    private Long amountMinor;
    private Long includedCreditMicros;
    private Integer validityDays;
    private String entitlementSnapshot;
    private LocalDateTime sellUntil;

    public String getProductCode() {
        return productCode;
    }

    public void setProductCode(String productCode) {
        this.productCode = productCode;
    }

    public String getProductType() {
        return productType;
    }

    public void setProductType(String productType) {
        this.productType = productType;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
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

    public LocalDateTime getSellUntil() {
        return sellUntil;
    }

    public void setSellUntil(LocalDateTime sellUntil) {
        this.sellUntil = sellUntil;
    }
}
