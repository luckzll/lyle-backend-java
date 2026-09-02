package com.example.aiapp.service.impl;

import com.example.aiapp.billing.BillingMetrics;
import com.example.aiapp.billing.BillingProperties;
import com.example.aiapp.billing.BillingRequests;
import com.example.aiapp.billing.payment.PaymentGateway;
import com.example.aiapp.service.BillingRefundService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
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

@Service
public class BillingRefundServiceImpl implements BillingRefundService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final BillingProperties properties;
    private final PaymentGateway paymentGateway;
    private final BillingMetrics metrics;

    public BillingRefundServiceImpl(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            BillingProperties properties,
            PaymentGateway paymentGateway,
            BillingMetrics metrics
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.paymentGateway = paymentGateway;
        this.metrics = metrics;
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> pendingRefundNumbers(int limit) {
        if (!properties.checkoutEnabled()) {
            return List.of();
        }
        return jdbcTemplate.query(
                """
                SELECT refund_no
                FROM refund_order
                WHERE status = 'PROCESSING'
                  AND updated_at <= DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 10 SECOND)
                ORDER BY updated_at
                LIMIT ?
                """,
                (resultSet, rowNum) -> resultSet.getString(1),
                Math.max(1, Math.min(limit, 100))
        );
    }

    @Override
    @Transactional
    public void refreshPendingRefund(String refundNo) {
        Map<String, Object> refund = requireRefund(refundNo, true);
        if (!"PROCESSING".equals(normalize(String.valueOf(refund.get("status"))))) {
            return;
        }
        PaymentGateway.RefundResult result = paymentGateway.queryRefund(
                String.valueOf(refund.get("payment_no")),
                refundNo
        );
        if ("SUCCEEDED".equals(normalize(result.status()))) {
            completeRefund(refundNo, false, refund, result);
        } else {
            jdbcTemplate.update(
                    "UPDATE refund_order SET updated_at = ? WHERE id = ?",
                    LocalDateTime.now(),
                    refund.get("id")
            );
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
        List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                "SELECT refund_no FROM refund_order WHERE order_id = ? AND request_id = ?",
                order.get("id"),
                requestId
        );
        if (!existing.isEmpty()) {
            metrics.duplicatePrevented();
            return requireRefund(String.valueOf(existing.get(0).get("refund_no")), false);
        }
        if (!"FULFILLED".equals(normalize(String.valueOf(order.get("status"))))) {
            throw conflict("只有已完成订单可以发起退款。");
        }
        Map<String, Object> payment = currentPayment(number(order.get("id")));
        if (payment == null || !"SUCCEEDED".equals(normalize(String.valueOf(payment.get("status"))))) {
            throw conflict("订单没有已确认的支付单。");
        }
        long refundable = number(order.get("paid_amount_minor")) - aggregateLong(
                """
                SELECT COALESCE(SUM(amount_minor), 0)
                FROM refund_order
                WHERE order_id = ? AND status IN ('PROCESSING', 'SUCCEEDED')
                """,
                order.get("id")
        );
        if (refundable <= 0) {
            throw conflict("订单已无可退款金额。");
        }
        boolean consumed = aggregateLong(
                """
                SELECT COALESCE(SUM(quantity_micros - remaining_micros), 0)
                FROM entitlement_grant
                WHERE source_type = 'ORDER' AND source_id = ?
                """,
                normalizedOrderNo
        ) > 0;
        String status = consumed ? "REVIEW_REQUIRED" : "PROCESSING";
        String refundNo = businessNo("R");
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                """
                INSERT INTO refund_order (
                    id, refund_no, request_id, order_id, payment_attempt_id,
                    user_id, status, currency, amount_minor, reason_code, reason_note,
                    requested_by, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                nextId(),
                refundNo,
                requestId,
                order.get("id"),
                payment.get("id"),
                order.get("user_id"),
                status,
                order.get("currency"),
                refundable,
                normalizeCode(request.reasonCode(), 64),
                safeNullable(request.reasonNote(), 512),
                operatorUserId,
                now,
                now
        );
        jdbcTemplate.update(
                "UPDATE billing_order SET status = 'REFUNDING', updated_at = ? WHERE id = ?",
                now,
                order.get("id")
        );
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
        jdbcTemplate.update(
                "UPDATE refund_order SET status = 'PROCESSING', updated_at = ? WHERE id = ?",
                LocalDateTime.now(),
                refund.get("id")
        );
        executeRefund(refundNo, true);
        return requireRefund(refundNo, false);
    }

    private void executeRefund(String refundNo, boolean approvedConsumed) {
        Map<String, Object> refund = requireRefund(refundNo, true);
        Map<String, Object> payment = requirePayment(String.valueOf(refund.get("payment_no")));
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
            jdbcTemplate.update(
                    "UPDATE refund_order SET status = 'PROCESSING', updated_at = ? WHERE id = ?",
                    LocalDateTime.now(),
                    refund.get("id")
            );
            return;
        }
        if (!"SUCCEEDED".equals(normalize(result.status()))) {
            jdbcTemplate.update(
                    "UPDATE refund_order SET status = 'FAILED', updated_at = ? WHERE id = ?",
                    LocalDateTime.now(),
                    refund.get("id")
            );
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
        List<Map<String, Object>> grants = jdbcTemplate.queryForList(
                """
                SELECT *
                FROM entitlement_grant
                WHERE source_type = 'ORDER' AND source_id = ?
                FOR UPDATE
                """,
                orderNo
        );
        for (Map<String, Object> grant : grants) {
            long remaining = number(grant.get("remaining_micros"));
            if (remaining > 0) {
                jdbcTemplate.update(
                        """
                        UPDATE entitlement_grant
                        SET remaining_micros = 0, status = 'REVOKED', updated_at = ?
                        WHERE id = ?
                        """,
                        LocalDateTime.now(),
                        grant.get("id")
                );
                long accountId = number(grant.get("account_id"));
                jdbcTemplate.update(
                        """
                        INSERT IGNORE INTO commercial_ledger_entry (
                            id, account_id, grant_id, entry_type, direction,
                            credit_micros, balance_after_micros, source_type, source_id,
                            idempotency_key, occurred_at, expires_at, metadata_json, created_at
                        ) VALUES (?, ?, ?, 'REFUND_REVERSAL', 'DEBIT', ?, ?, 'REFUND', ?, ?, ?, ?, ?, ?)
                        """,
                        nextId(),
                        accountId,
                        grant.get("id"),
                        remaining,
                        availableCredits(accountId),
                        refundNo,
                        safe("REFUND_REVERSAL:" + refundNo + ":" + grant.get("id"), 128),
                        LocalDateTime.now(),
                        grant.get("expires_at"),
                        json(Map.of("consumedApproved", approvedConsumed)),
                        LocalDateTime.now()
                );
            } else {
                jdbcTemplate.update(
                        "UPDATE entitlement_grant SET status = 'REVOKED', updated_at = ? WHERE id = ?",
                        LocalDateTime.now(),
                        grant.get("id")
                );
            }
        }
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                """
                UPDATE refund_order
                SET status = 'SUCCEEDED', provider_refund_id = ?, succeeded_at = ?, updated_at = ?
                WHERE id = ?
                """,
                result.providerRefundId(),
                now,
                now,
                refund.get("id")
        );
        jdbcTemplate.update(
                "UPDATE billing_order SET status = 'REFUNDED', updated_at = ? WHERE id = ?",
                now,
                refund.get("order_id")
        );
        metrics.refundSucceeded();
    }

    private Map<String, Object> requireOrder(String orderNo, boolean forUpdate) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM billing_order WHERE order_no = ?" + (forUpdate ? " FOR UPDATE" : ""),
                safe(orderNo, 64)
        );
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return rows.get(0);
    }

    private Map<String, Object> requirePayment(String paymentNo) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT p.*, o.order_no, o.status AS order_status, o.user_id, o.account_id
                FROM payment_attempt p
                JOIN billing_order o ON o.id = p.order_id
                WHERE p.payment_no = ?
                """,
                safe(paymentNo, 64)
        );
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return rows.get(0);
    }

    private Map<String, Object> requireRefund(String refundNo, boolean forUpdate) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT r.*, o.order_no, p.payment_no
                FROM refund_order r
                JOIN billing_order o ON o.id = r.order_id
                JOIN payment_attempt p ON p.id = r.payment_attempt_id
                WHERE r.refund_no = ?
                """ + (forUpdate ? " FOR UPDATE" : ""),
                safe(refundNo, 64)
        );
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return rows.get(0);
    }

    private Map<String, Object> currentPayment(long orderId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT *
                FROM payment_attempt
                WHERE order_id = ?
                ORDER BY created_at DESC, id DESC
                LIMIT 1
                """,
                orderId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long availableCredits(long accountId) {
        return aggregateLong(
                """
                SELECT COALESCE(SUM(remaining_micros), 0)
                FROM entitlement_grant
                WHERE account_id = ? AND status = 'ACTIVE'
                  AND remaining_micros > 0
                  AND valid_from <= CURRENT_TIMESTAMP(6)
                  AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP(6))
                """,
                accountId
        );
    }

    private long aggregateLong(String sql, Object... arguments) {
        Long result = jdbcTemplate.queryForObject(sql, Long.class, arguments);
        return result == null ? 0 : result;
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
