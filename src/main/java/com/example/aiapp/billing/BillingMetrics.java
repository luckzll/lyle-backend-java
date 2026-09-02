package com.example.aiapp.billing;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class BillingMetrics {

    private final Counter ordersCreated;
    private final Counter ordersPaid;
    private final Counter ordersFulfilled;
    private final Counter invalidCallbacks;
    private final Counter commercialDebits;
    private final Counter commercialInsufficient;
    private final Counter duplicatePrevented;
    private final Counter reconciliationDifferences;
    private final Counter refundsSucceeded;
    private final Timer fulfillmentLatency;

    public BillingMetrics(MeterRegistry registry) {
        ordersCreated = registry.counter("billing.order.created.total");
        ordersPaid = registry.counter("billing.order.paid.total");
        ordersFulfilled = registry.counter("billing.order.fulfilled.total");
        invalidCallbacks = registry.counter("billing.payment.callback.invalid.total");
        commercialDebits = registry.counter("billing.commercial.debit.credit_micros");
        commercialInsufficient = registry.counter("billing.commercial.insufficient.total");
        duplicatePrevented = registry.counter("billing.duplicate.prevented.total");
        reconciliationDifferences = registry.counter("billing.reconciliation.diff.total");
        refundsSucceeded = registry.counter("billing.refund.succeeded.total");
        fulfillmentLatency = registry.timer("billing.fulfillment.latency");
    }

    public void orderCreated() {
        ordersCreated.increment();
    }

    public void orderPaid() {
        ordersPaid.increment();
    }

    public void orderFulfilled(Duration latency) {
        ordersFulfilled.increment();
        if (latency != null && !latency.isNegative()) {
            fulfillmentLatency.record(latency);
        }
    }

    public void invalidCallback() {
        invalidCallbacks.increment();
    }

    public void commercialDebit(long creditMicros) {
        commercialDebits.increment(Math.max(0, creditMicros));
    }

    public void commercialInsufficient() {
        commercialInsufficient.increment();
    }

    public void duplicatePrevented() {
        duplicatePrevented.increment();
    }

    public void reconciliationDifferences(long count) {
        reconciliationDifferences.increment(Math.max(0, count));
    }

    public void refundSucceeded() {
        refundsSucceeded.increment();
    }
}
