package com.example.aiapp.service;

import com.example.aiapp.billing.BillingRequests;
import com.example.aiapp.usage.UsageEventRequest;

import java.util.List;
import java.util.Map;

/**
 * 商业账户与额度服务。
 */
public interface BillingAccountService {

    Map<String, Object> account(long userId);

    List<Map<String, Object>> ledger(long userId, String type, int limit);

    List<String> pendingFulfillmentOrders(int limit);

    List<Long> expiredGrantIds(int limit);

    void expireGrant(long grantId);

    void fulfillOrder(String orderNo);

    void markFulfillmentFailed(String orderNo, Exception exception);

    String reserveCommercial(long userId, String turnId);

    void releaseCommercialReservation(String reservationId);

    void settleUsage(UsageEventRequest event);

    List<String> pendingUsageEventIds(int limit);

    void settleUsageEvent(String eventId);

    Map<String, Object> publishUsagePricing(BillingRequests.PublishUsagePricing request);

    Map<String, Object> grantManualCredits(long userId, BillingRequests.GrantCredits request);
}
