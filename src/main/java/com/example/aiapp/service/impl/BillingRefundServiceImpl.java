package com.example.aiapp.service.impl;

import com.example.aiapp.billing.BillingMetrics;
import com.example.aiapp.billing.BillingProperties;
import com.example.aiapp.billing.BillingRequests;
import com.example.aiapp.billing.payment.PaymentGateway;
import com.example.aiapp.billing.payment.PaymentGatewayRegistry;
import com.example.aiapp.domain.entity.RefundOrderEntity;
import com.example.aiapp.mapper.RefundOrderMapper;
import com.example.aiapp.service.BillingRefundService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.example.aiapp.billing.support.BillingIdentifiers.businessNo;
import static com.example.aiapp.billing.support.BillingIdentifiers.nextId;

/**
 * 退款申请与处理服务实现。
 */
@Service
public class BillingRefundServiceImpl implements BillingRefundService {

    private final RefundOrderMapper refundOrderMapper;
    private final ObjectMapper objectMapper;
    private final BillingProperties properties;
    private final PaymentGatewayRegistry paymentGatewayRegistry;
    private final BillingMetrics metrics;

    public BillingRefundServiceImpl(
            RefundOrderMapper refundOrderMapper,
            ObjectMapper objectMapper,
            BillingProperties properties,
            PaymentGatewayRegistry paymentGatewayRegistry,
            BillingMetrics metrics
    ) {
        this.refundOrderMapper = refundOrderMapper;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.paymentGatewayRegistry = paymentGatewayRegistry;
        this.metrics = metrics;
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> pendingRefundNumbers(int limit) {
        if (!properties.checkoutEnabled()) {
            return List.of();
        }
        return refundOrderMapper.selectPendingRefundNumbers(Math.max(1, Math.min(limit, 100)));
    }

    @Override
    @Transactional
    public void refreshPendingRefund(String refundNo) {
        Map<String, Object> refund = requireRefund(refundNo, true);
        if (!"PROCESSING".equals(normalize(String.valueOf(refund.get("status"))))) {
            return;
        }
        Map<String, Object> payment = requirePayment(String.valueOf(refund.get("payment_no")));
        PaymentGateway paymentGateway = gatewayFor(payment);
        PaymentGateway.RefundResult result = paymentGateway.queryRefund(
                String.valueOf(refund.get("payment_no")),
                refundNo
        );
        if ("SUCCEEDED".equals(normalize(result.status()))) {
            completeRefund(refundNo, false, refund, result);
        } else {
            refundOrderMapper.touchRefund(number(refund.get("id")), LocalDateTime.now());
        }
    }

    @Override
    @Transactional
    public Map<String, Object> createRefund(
            long operatorUserId,
            String orderNo,
            BillingRequests.CreateRefund request
    ) {
        String normalizedOrderNo = safe(orderNo, 64);
        String requestId = safe(request.requestId(), 128);
        Map<String, Object> order = requireOrder(normalizedOrderNo, true);
        String existingRefundNo = refundOrderMapper.selectRefundNoByOrderIdAndRequestId(
                number(order.get("id")),
                requestId
        );
        if (existingRefundNo != null) {
            metrics.duplicatePrevented();
            return requireRefund(existingRefundNo, false);
        }
        if (!"FULFILLED".equals(normalize(String.valueOf(order.get("status"))))) {
            throw conflict("只有已完成订单可以发起退款。");
        }
        Map<String, Object> payment = currentPayment(number(order.get("id")));
        if (payment == null || !"SUCCEEDED".equals(normalize(String.valueOf(payment.get("status"))))) {
            throw conflict("订单没有已确认的支付单。");
        }
        long refundable = number(order.get("paid_amount_minor"))
                - refundOrderMapper.sumActiveRefundAmount(number(order.get("id")));
        if (refundable <= 0) {
            throw conflict("订单已无可退款金额。");
        }
        boolean consumed = refundOrderMapper.sumConsumedGrantByOrderNo(normalizedOrderNo) > 0;
        String status = consumed ? "REVIEW_REQUIRED" : "PROCESSING";
        String refundNo = businessNo("R");
        LocalDateTime now = LocalDateTime.now();
        RefundOrderEntity refund = new RefundOrderEntity();
        refund.setId(nextId());
        refund.setRefundNo(refundNo);
        refund.setRequestId(requestId);
        refund.setOrderId(number(order.get("id")));
        refund.setPaymentAttemptId(number(payment.get("id")));
        refund.setUserId(number(order.get("user_id")));
        refund.setStatus(status);
        refund.setCurrency(String.valueOf(order.get("currency")));
        refund.setAmountMinor(refundable);
        refund.setReasonCode(normalizeCode(request.reasonCode(), 64));
        refund.setReasonNote(safeNullable(request.reasonNote(), 512));
        refund.setRequestedBy(operatorUserId);
        refund.setCreatedAt(now);
        refund.setUpdatedAt(now);
        refundOrderMapper.insert(refund);
        refundOrderMapper.markOrderRefunding(number(order.get("id")), now);
        if (!consumed) {
            executeRefund(refundNo, false);
        }
        return requireRefund(refundNo, false);
    }

    @Override
    @Transactional
    public Map<String, Object> approveRefund(String refundNo) {
        Map<String, Object> refund = requireRefund(refundNo, true);
        if ("SUCCEEDED".equals(normalize(String.valueOf(refund.get("status"))))) {
            return refund;
        }
        if (!"REVIEW_REQUIRED".equals(normalize(String.valueOf(refund.get("status"))))) {
            throw conflict("退款单当前不需要人工审批。");
        }
        refundOrderMapper.markRefundProcessing(number(refund.get("id")), LocalDateTime.now());
        executeRefund(refundNo, true);
        return requireRefund(refundNo, false);
    }

    private void executeRefund(String refundNo, boolean approvedConsumed) {
        Map<String, Object> refund = requireRefund(refundNo, true);
        Map<String, Object> payment = requirePayment(String.valueOf(refund.get("payment_no")));
        PaymentGateway paymentGateway = gatewayFor(payment);
        PaymentGateway.RefundResult result = paymentGateway.createRefund(
                new PaymentGateway.CreateRefundCommand(
                        refundNo,
                        String.valueOf(payment.get("payment_no")),
                        number(refund.get("amount_minor")),
                        number(payment.get("amount_minor")),
                        String.valueOf(refund.get("currency")),
                        String.valueOf(refund.get("reason_code"))
                )
        );
        if ("PENDING".equals(normalize(result.status()))) {
            refundOrderMapper.markRefundProcessing(number(refund.get("id")), LocalDateTime.now());
            return;
        }
        if (!"SUCCEEDED".equals(normalize(result.status()))) {
            refundOrderMapper.markRefundFailed(number(refund.get("id")), LocalDateTime.now());
            return;
        }
        completeRefund(refundNo, approvedConsumed, refund, result);
    }

    private void completeRefund(
            String refundNo,
            boolean approvedConsumed,
            Map<String, Object> refund,
            PaymentGateway.RefundResult result
    ) {
        String orderNo = String.valueOf(refund.get("order_no"));
        refundOrderMapper.lockOrderAccount(orderNo);
        List<Map<String, Object>> grants = refundOrderMapper.selectOrderGrantsForUpdate(orderNo);
        for (Map<String, Object> grant : grants) {
            long remaining = number(grant.get("remaining_micros"));
            if (remaining > 0) {
                refundOrderMapper.revokeGrantAndClear(number(grant.get("id")), LocalDateTime.now());
                long accountId = number(grant.get("account_id"));
                LocalDateTime occurredAt = LocalDateTime.now();
                refundOrderMapper.insertRefundReversal(
                        nextId(),
                        accountId,
                        number(grant.get("id")),
                        remaining,
                        availableCredits(accountId),
                        refundNo,
                        safe("REFUND_REVERSAL:" + refundNo + ":" + grant.get("id"), 128),
                        occurredAt,
                        grant.get("expires_at"),
                        json(Map.of("consumedApproved", approvedConsumed)),
                        LocalDateTime.now()
                );
            } else {
                refundOrderMapper.revokeGrant(number(grant.get("id")), LocalDateTime.now());
            }
        }
        LocalDateTime now = LocalDateTime.now();
        refundOrderMapper.markRefundSucceeded(
                number(refund.get("id")),
                result.providerRefundId(),
                now,
                now
        );
        refundOrderMapper.markOrderRefunded(number(refund.get("order_id")), now);
        metrics.refundSucceeded();
    }

    private PaymentGateway gatewayFor(Map<String, Object> payment) {
        return paymentGatewayRegistry.requireForStoredProvider(String.valueOf(payment.get("provider")));
    }

    private Map<String, Object> requireOrder(String orderNo, boolean forUpdate) {
        Map<String, Object> order = refundOrderMapper.selectOrderByOrderNo(safe(orderNo, 64), forUpdate);
        if (order == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return order;
    }

    private Map<String, Object> requirePayment(String paymentNo) {
        Map<String, Object> payment = refundOrderMapper.selectPaymentByPaymentNo(safe(paymentNo, 64));
        if (payment == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return payment;
    }

    private Map<String, Object> requireRefund(String refundNo, boolean forUpdate) {
        Map<String, Object> refund = refundOrderMapper.selectRefundByRefundNo(safe(refundNo, 64), forUpdate);
        if (refund == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return refund;
    }

    private Map<String, Object> currentPayment(long orderId) {
        return refundOrderMapper.selectLatestPaymentByOrderId(orderId);
    }

    private long availableCredits(long accountId) {
        return refundOrderMapper.sumAvailableCredits(accountId);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static long number(Object value) {
        if (value == null) return 0;
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeCode(String value, int maxLength) {
        String result = normalize(value);
        if (!result.matches("[A-Z0-9][A-Z0-9._:-]*") || result.length() > maxLength) {
            throw badRequest("业务编码格式无效。");
        }
        return result;
    }

    private static String safe(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            throw badRequest("必填字段不能为空。");
        }
        String result = value.trim();
        if (result.length() > maxLength) {
            throw badRequest("字段长度超出限制。");
        }
        return result;
    }

    private static String safeNullable(String value, int maxLength) {
        return StringUtils.hasText(value) ? safe(value, maxLength) : null;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
