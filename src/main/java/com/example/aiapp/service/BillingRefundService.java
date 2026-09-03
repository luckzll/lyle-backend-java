package com.example.aiapp.service;

import com.example.aiapp.billing.BillingRequests;

import java.util.List;
import java.util.Map;

/**
 * 退款申请与处理服务。
 */
public interface BillingRefundService {

    List<String> pendingRefundNumbers(int limit);

    void refreshPendingRefund(String refundNo);

    Map<String, Object> createRefund(
            long operatorUserId,
            String orderNo,
            BillingRequests.CreateRefund request
    );

    Map<String, Object> approveRefund(String refundNo);
}
