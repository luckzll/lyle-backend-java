package com.example.aiapp.billing;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class BillingSettlementWorker {

    private final BillingService billingService;

    public BillingSettlementWorker(BillingService billingService) {
        this.billingService = billingService;
    }

    @Scheduled(fixedDelayString = "${app.billing.settlement-scan-millis:10000}")
    public void settleUnbilledUsage() {
        for (String eventId : billingService.pendingUsageEventIds(100)) {
            try {
                billingService.settleUsageEvent(eventId);
            } catch (RuntimeException ignored) {
                // A later scan retries the same immutable usageEventId. The
                // billable_usage unique key keeps successful retries idempotent.
            }
        }
    }
}
