package com.example.aiapp.service;

import com.example.aiapp.billing.BillingRequests;

import java.util.List;
import java.util.Map;

/**
 * 支付对账服务。
 */
public interface BillingReconciliationService {

    List<Map<String, Object>> list(String status, int limit);

    Map<String, Object> reconcile(BillingRequests.Reconcile request);

    Map<String, Object> resolve(
            long id,
            long operatorUserId,
            BillingRequests.ResolveReconciliation request
    );
}
