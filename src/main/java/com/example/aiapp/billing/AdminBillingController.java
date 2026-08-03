package com.example.aiapp.billing;

import com.example.aiapp.service.AuditLogService;
import com.example.aiapp.utils.CurrentUserUtils;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/billing")
public class AdminBillingController {

    private final BillingService billingService;
    private final AuditLogService auditLogService;

    public AdminBillingController(
            BillingService billingService,
            AuditLogService auditLogService
    ) {
        this.billingService = billingService;
        this.auditLogService = auditLogService;
    }

    @PostMapping("/products")
    Map<String, Object> createProduct(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody BillingRequests.CreateProduct request
    ) {
        Map<String, Object> result = billingService.createProduct(request);
        audit("BILLING_PRODUCT_CREATE", request.productCode());
        return result;
    }

    @PostMapping("/prices")
    Map<String, Object> createPrice(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody BillingRequests.CreatePrice request
    ) {
        Map<String, Object> result = billingService.createPrice(request);
        audit("BILLING_PRICE_CREATE", request.versionCode());
        return result;
    }

    @PostMapping("/prices/{versionCode}/publish")
    Map<String, Object> publishPrice(
            @PathVariable String versionCode,
            @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        Map<String, Object> result = billingService.publishPrice(versionCode);
        audit("BILLING_PRICE_PUBLISH", versionCode);
        return result;
    }

    @PostMapping("/usage-pricing")
    Map<String, Object> publishUsagePricing(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody BillingRequests.PublishUsagePricing request
    ) {
        Map<String, Object> result = billingService.publishUsagePricing(request);
        audit("BILLING_USAGE_PRICING_PUBLISH", request.versionCode());
        return result;
    }

    @PostMapping("/accounts/{userId}/grants")
    Map<String, Object> grantCredits(
            @PathVariable long userId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody BillingRequests.GrantCredits request
    ) {
        Map<String, Object> result = billingService.grantManualCredits(userId, request);
        audit("BILLING_CREDIT_GRANT", String.valueOf(userId));
        return result;
    }

    @PostMapping("/payments/{paymentNo}/mock:complete")
    Map<String, Object> completeMockPayment(
            @PathVariable String paymentNo,
            @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        Map<String, Object> result = billingService.confirmMockPayment(paymentNo);
        audit("BILLING_MOCK_PAYMENT_COMPLETE", paymentNo);
        return result;
    }

    @PostMapping("/orders/{orderNo}/fulfillment:retry")
    Map<String, Object> retryFulfillment(
            @PathVariable String orderNo,
            @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        billingService.fulfillOrder(orderNo);
        audit("BILLING_FULFILLMENT_RETRY", orderNo);
        return Map.of("status", "FULFILLED", "orderNo", orderNo);
    }

    @PostMapping("/orders/{orderNo}/refunds")
    Map<String, Object> refund(
            @PathVariable String orderNo,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody BillingRequests.CreateRefund request
    ) {
        Map<String, Object> result = billingService.createRefund(
                CurrentUserUtils.currentUserId(),
                orderNo,
                request
        );
        audit("BILLING_REFUND_CREATE", orderNo);
        return result;
    }

    @PostMapping("/refunds/{refundNo}:approve")
    Map<String, Object> approveRefund(
            @PathVariable String refundNo,
            @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        Map<String, Object> result = billingService.approveRefund(refundNo);
        audit("BILLING_REFUND_APPROVE", refundNo);
        return result;
    }

    @GetMapping("/reconciliation")
    List<Map<String, Object>> reconciliation(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit
    ) {
        return billingService.reconciliation(status, limit);
    }

    @PostMapping("/reconciliation:run")
    Map<String, Object> reconcile(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody BillingRequests.Reconcile request
    ) {
        Map<String, Object> result = billingService.reconcile(request);
        audit("BILLING_RECONCILIATION_RUN", request.date().toString());
        return result;
    }

    @PostMapping("/reconciliation/{id}/resolve")
    Map<String, Object> resolveReconciliation(
            @PathVariable long id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody BillingRequests.ResolveReconciliation request
    ) {
        Map<String, Object> result = billingService.resolveReconciliation(
                id,
                CurrentUserUtils.currentUserId(),
                request
        );
        audit("BILLING_RECONCILIATION_RESOLVE", String.valueOf(id));
        return result;
    }

    private void audit(String action, String targetId) {
        auditLogService.record(
                CurrentUserUtils.currentUserId(),
                action,
                "BILLING",
                targetId
        );
    }
}
