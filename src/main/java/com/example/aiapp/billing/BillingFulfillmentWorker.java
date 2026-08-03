package com.example.aiapp.billing;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class BillingFulfillmentWorker {

    private final BillingService billingService;

    public BillingFulfillmentWorker(BillingService billingService) {
        this.billingService = billingService;
    }

    @Scheduled(fixedDelayString = "${app.billing.fulfillment-scan-millis:2000}")
    public void fulfillReadyOrders() {
        for (String orderNo : billingService.pendingFulfillmentOrders(20)) {
            try {
                billingService.fulfillOrder(orderNo);
            } catch (Exception exception) {
                billingService.markFulfillmentFailed(orderNo, exception);
            }
        }
    }
}
