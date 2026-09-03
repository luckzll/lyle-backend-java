package com.example.aiapp.billing;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class BillingRequests {

    private BillingRequests() {
    }

    public record CreateOrder(
            @NotBlank @Size(max = 64) String priceVersionCode,
            @NotBlank @Size(max = 32) String purchaseIntent,
            @NotBlank @Size(max = 128) String clientRequestId
    ) {
    }

    public record CreateProduct(
            @NotBlank @Size(max = 64) String productCode,
            @NotBlank @Size(max = 32) String productType,
            @NotBlank @Size(max = 128) String displayName,
            @Size(max = 512) String description
    ) {
    }

    public record CreatePrice(
            @NotBlank @Size(max = 64) String productCode,
            @NotBlank @Size(max = 64) String versionCode,
            @NotBlank @Size(max = 3) String currency,
            @Min(0) long amountMinor,
            @Min(0) long includedCreditMicros,
            Integer validityDays,
            List<String> benefits,
            Map<String, Object> pricingRules,
            LocalDateTime sellFrom,
            LocalDateTime sellUntil
    ) {
    }

    public record PublishUsagePricing(
            @NotBlank @Size(max = 64) String versionCode,
            @NotNull Map<String, Object> rules,
            LocalDateTime effectiveFrom,
            LocalDateTime effectiveUntil
    ) {
    }

    public record GrantCredits(
            @NotBlank @Size(max = 128) String requestId,
            @Min(1) long creditMicros,
            Integer validityDays,
            @Size(max = 512) String reason
    ) {
    }

    public record CreateRefund(
            @NotBlank @Size(max = 128) String requestId,
            @NotBlank @Size(max = 64) String reasonCode,
            @Size(max = 512) String reasonNote
    ) {
    }

    public record ResolveReconciliation(
            @NotBlank @Size(max = 512) String note
    ) {
    }

    public record ChannelTransaction(
            @NotBlank @Size(max = 64) String paymentNo,
            @Size(max = 128) String providerTransactionId,
            @Min(0) long amountMinor,
            @NotBlank @Size(max = 32) String status
    ) {
    }

    public record Reconcile(
            @NotNull LocalDate date,
            @Size(max = 32) String provider,
            @NotNull List<ChannelTransaction> transactions
    ) {
    }
}
