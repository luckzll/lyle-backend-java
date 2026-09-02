package com.example.aiapp.billing;

import com.example.aiapp.billing.payment.WechatPayPaymentGateway;
import com.example.aiapp.service.BillingAccountService;
import com.example.aiapp.service.BillingCatalogService;
import com.example.aiapp.service.BillingOrderQueryService;
import com.example.aiapp.service.BillingPaymentService;
import com.example.aiapp.service.BillingReconciliationService;
import com.example.aiapp.service.BillingRefundService;
import com.example.aiapp.usage.UsageEventRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Backward-compatible billing facade.
 *
 * <p>Controllers, workers, the chat service and the usage service keep depending
 * on this stable entry point. Business rules live in focused service modules so
 * API paths and cross-service contracts do not change during the refactor.</p>
 */
@Service
public class BillingService {

    private final BillingProperties properties;
    private final BillingCatalogService catalogService;
    private final BillingOrderQueryService orderQueryService;
    private final BillingPaymentService paymentService;
    private final BillingAccountService accountService;
    private final BillingRefundService refundService;
    private final BillingReconciliationService reconciliationService;

    public BillingService(
            BillingProperties properties,
            BillingCatalogService catalogService,
            BillingOrderQueryService orderQueryService,
            BillingPaymentService paymentService,
            BillingAccountService accountService,
            BillingRefundService refundService,
            BillingReconciliationService reconciliationService
    ) {
        this.properties = properties;
        this.catalogService = catalogService;
        this.orderQueryService = orderQueryService;
        this.paymentService = paymentService;
        this.accountService = accountService;
        this.refundService = refundService;
        this.reconciliationService = reconciliationService;
    }

    public List<Map<String, Object>> catalog() {
        require(properties.catalogEnabled());
        return catalogService.listPublishedCatalog().stream()
                .map(item -> item.toMap())
                .toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> account(long userId) {
        return accountService.account(userId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<Map<String, Object>> ledger(long userId, String type, int limit) {
        return accountService.ledger(userId, type, limit);
    }

    @Transactional
    public Map<String, Object> createOrder(long userId, BillingRequests.CreateOrder request) {
        return paymentService.createOrder(userId, request);
    }

    public List<Map<String, Object>> orders(long userId, int limit) {
        require(properties.userViewEnabled());
        return orderQueryService.listOrders(userId, limit);
    }

    public Map<String, Object> order(long userId, String orderNo) {
        require(properties.userViewEnabled());
        return orderQueryService.getOrder(userId, orderNo);
    }

    @Transactional
    public Map<String, Object> refreshOrderStatus(long userId, String orderNo) {
        return paymentService.refreshOrderStatus(userId, orderNo);
    }

    @Transactional
    public Map<String, Object> checkout(long userId, String orderNo) {
        return paymentService.checkout(userId, orderNo);
    }

    @Transactional
    public Map<String, Object> closeOrder(long userId, String orderNo) {
        return paymentService.closeOrder(userId, orderNo);
    }

    @Transactional
    public Map<String, Object> confirmMockPayment(String paymentNo) {
        return paymentService.confirmMockPayment(paymentNo);
    }

    @Transactional
    public void confirmPayment(
            String paymentNo,
            String providerTransactionId,
            long amountMinor,
            String currency,
            String provider
    ) {
        paymentService.confirmPayment(
                paymentNo,
                providerTransactionId,
                amountMinor,
                currency,
                provider
        );
    }

    public Map<String, Object> paymentStatus(String paymentNo) {
        return paymentService.paymentStatus(paymentNo);
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public Map<String, Object> processWebhook(
            String provider,
            byte[] rawBody,
            String signature
    ) {
        return paymentService.processWebhook(provider, rawBody, signature);
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public boolean processAlipayNotification(Map<String, String> parameters) {
        return paymentService.processAlipayNotification(parameters);
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public boolean processWechatPayNotification(
            WechatPayPaymentGateway.VerifiedPaymentNotification notification
    ) {
        return paymentService.processWechatPayNotification(notification);
    }

    public List<String> pendingFulfillmentOrders(int limit) {
        return accountService.pendingFulfillmentOrders(limit);
    }

    public List<String> expiredPendingOrderNumbers(int limit) {
        return paymentService.expiredPendingOrderNumbers(limit);
    }

    @Transactional
    public void closeExpiredPendingOrder(String orderNo) {
        paymentService.closeExpiredPendingOrder(orderNo);
    }

    public List<Long> expiredGrantIds(int limit) {
        return accountService.expiredGrantIds(limit);
    }

    public List<String> pendingRefundNumbers(int limit) {
        return refundService.pendingRefundNumbers(limit);
    }

    @Transactional
    public void refreshPendingRefund(String refundNo) {
        refundService.refreshPendingRefund(refundNo);
    }

    @Transactional
    public void expireGrant(long grantId) {
        accountService.expireGrant(grantId);
    }

    @Transactional
    public void fulfillOrder(String orderNo) {
        accountService.fulfillOrder(orderNo);
    }

    public void markFulfillmentFailed(String orderNo, Exception exception) {
        accountService.markFulfillmentFailed(orderNo, exception);
    }

    @Transactional
    public String reserveCommercial(long userId, String turnId) {
        return accountService.reserveCommercial(userId, turnId);
    }

    public void releaseCommercialReservation(String reservationId) {
        accountService.releaseCommercialReservation(reservationId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void settleUsage(UsageEventRequest event) {
        accountService.settleUsage(event);
    }

    public List<String> pendingUsageEventIds(int limit) {
        return accountService.pendingUsageEventIds(limit);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void settleUsageEvent(String eventId) {
        accountService.settleUsageEvent(eventId);
    }

    @Transactional
    public Map<String, Object> createProduct(BillingRequests.CreateProduct request) {
        return catalogService.createProduct(request);
    }

    @Transactional
    public Map<String, Object> createPrice(BillingRequests.CreatePrice request) {
        return catalogService.createPrice(request);
    }

    @Transactional
    public Map<String, Object> publishPrice(String versionCode) {
        return catalogService.publishPrice(versionCode);
    }

    @Transactional
    public Map<String, Object> publishUsagePricing(
            BillingRequests.PublishUsagePricing request
    ) {
        return accountService.publishUsagePricing(request);
    }

    @Transactional
    public Map<String, Object> grantManualCredits(
            long userId,
            BillingRequests.GrantCredits request
    ) {
        return accountService.grantManualCredits(userId, request);
    }

    public List<Map<String, Object>> refunds(long userId) {
        require(properties.userViewEnabled());
        return orderQueryService.listRefunds(userId);
    }

    @Transactional
    public Map<String, Object> createRefund(
            long operatorUserId,
            String orderNo,
            BillingRequests.CreateRefund request
    ) {
        return refundService.createRefund(operatorUserId, orderNo, request);
    }

    @Transactional
    public Map<String, Object> approveRefund(String refundNo) {
        return refundService.approveRefund(refundNo);
    }

    public List<Map<String, Object>> reconciliation(String status, int limit) {
        return reconciliationService.list(status, limit);
    }

    @Transactional
    public Map<String, Object> reconcile(BillingRequests.Reconcile request) {
        return reconciliationService.reconcile(request);
    }

    @Transactional
    public Map<String, Object> resolveReconciliation(
            long id,
            long operatorUserId,
            BillingRequests.ResolveReconciliation request
    ) {
        return reconciliationService.resolve(id, operatorUserId, request);
    }

    private static void require(boolean enabled) {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }
}
