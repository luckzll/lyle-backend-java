package com.example.aiapp.billing;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class BillingMaintenanceWorker {

    private final BillingService billingService;

    public BillingMaintenanceWorker(BillingService billingService) {
        this.billingService = billingService;
    }

    @Scheduled(fixedDelayString = "${app.billing.maintenance-scan-millis:60000}")
    public void closeExpiredOrdersAndCredits() {
        for (String orderNo : billingService.expiredPendingOrderNumbers(100)) {
            try {
                billingService.closeExpiredPendingOrder(orderNo);
            } catch (RuntimeException ignored) {
                // Unknown provider state is intentionally retained for retry.
            }
        }
        for (Long grantId : billingService.expiredGrantIds(200)) {
            try {
                billingService.expireGrant(grantId);
            } catch (RuntimeException ignored) {
                // The immutable grant and idempotency key make the next scan safe.
            }
        }
        for (String refundNo : billingService.pendingRefundNumbers(100)) {
            try {
                billingService.refreshPendingRefund(refundNo);
            } catch (RuntimeException ignored) {
                // Unknown provider state remains PROCESSING and is queried again later.
            }
        }
    }
}
