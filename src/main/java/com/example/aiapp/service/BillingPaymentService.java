package com.example.aiapp.service;

import com.example.aiapp.billing.BillingRequests;
import com.example.aiapp.billing.payment.WechatPayPaymentGateway;

import java.util.List;
import java.util.Map;

/**
 * 支付下单与回调处理服务。
 */
public interface BillingPaymentService {

    Map<String, Object> createOrder(long userId, BillingRequests.CreateOrder request);

    Map<String, Object> refreshOrderStatus(long userId, String orderNo);

    Map<String, Object> checkout(long userId, String orderNo, String provider);

    Map<String, Object> closeOrder(long userId, String orderNo);

    void deleteOrder(long userId, String orderNo);

    Map<String, Object> confirmMockPayment(String paymentNo);

    void confirmPayment(
            String paymentNo,
            String providerTransactionId,
            long amountMinor,
            String currency,
            String provider
    );

    Map<String, Object> paymentStatus(String paymentNo);

    Map<String, Object> processWebhook(String provider, byte[] rawBody, String signature);

    boolean processAlipayNotification(Map<String, String> parameters);

    boolean processWechatPayNotification(
            WechatPayPaymentGateway.VerifiedPaymentNotification notification
    );

    List<String> expiredPendingOrderNumbers(int limit);

    void closeExpiredPendingOrder(String orderNo);
}
