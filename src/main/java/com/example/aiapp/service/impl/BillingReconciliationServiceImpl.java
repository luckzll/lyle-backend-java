package com.example.aiapp.service.impl;

import com.example.aiapp.billing.BillingMetrics;
import com.example.aiapp.billing.BillingProperties;
import com.example.aiapp.billing.BillingRequests;
import com.example.aiapp.mapper.PaymentReconciliationItemMapper;
import com.example.aiapp.service.BillingReconciliationService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.example.aiapp.billing.support.BillingIdentifiers.nextId;

/**
 * 支付对账服务实现。
 */
@Service
public class BillingReconciliationServiceImpl implements BillingReconciliationService {

    private final PaymentReconciliationItemMapper reconciliationItemMapper;
    private final BillingProperties properties;
    private final BillingMetrics metrics;

    public BillingReconciliationServiceImpl(
            PaymentReconciliationItemMapper reconciliationItemMapper,
            BillingProperties properties,
            BillingMetrics metrics
    ) {
        this.reconciliationItemMapper = reconciliationItemMapper;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status, int limit) {
        require(properties.reconciliationEnabled());
        String normalizedStatus = normalizeNullable(status);
        int boundedLimit = Math.max(1, Math.min(limit, 200));
        return reconciliationItemMapper.selectRecent(normalizedStatus, boundedLimit);
    }

    @Override
    @Transactional
    public Map<String, Object> reconcile(BillingRequests.Reconcile request) {
        require(properties.reconciliationEnabled());
        String provider = normalize(properties.paymentProvider());
        Map<String, BillingRequests.ChannelTransaction> channel = new LinkedHashMap<>();
        for (BillingRequests.ChannelTransaction transaction : request.transactions()) {
            channel.put(safe(transaction.paymentNo(), 64), transaction);
        }
        LocalDateTime start = request.date().atStartOfDay();
        LocalDateTime end = request.date().plusDays(1).atStartOfDay();
        List<Map<String, Object>> internal = reconciliationItemMapper.selectPaymentsForReconciliation(
                provider,
                start,
                end
        );
        int matched = 0;
        int differences = 0;
        for (Map<String, Object> payment : internal) {
            String paymentNo = String.valueOf(payment.get("payment_no"));
            BillingRequests.ChannelTransaction transaction = channel.remove(paymentNo);
            String difference;
            Long providerAmount = null;
            String providerStatus = null;
            String providerTransactionId = null;
            if (transaction == null) {
                difference = "SYSTEM_ONLY";
            } else {
                providerAmount = transaction.amountMinor();
                providerStatus = normalize(transaction.status());
                providerTransactionId = transaction.providerTransactionId();
                if (number(payment.get("amount_minor")) != transaction.amountMinor()) {
                    difference = "AMOUNT_MISMATCH";
                } else if (!normalize(String.valueOf(payment.get("status"))).equals(providerStatus)) {
                    difference = "STATUS_MISMATCH";
                } else {
                    difference = "MATCHED";
                }
            }
            upsertReconciliation(
                    request.date(),
                    provider,
                    paymentNo,
                    providerTransactionId,
                    number(payment.get("amount_minor")),
                    providerAmount,
                    String.valueOf(payment.get("status")),
                    providerStatus,
                    difference
            );
            if ("MATCHED".equals(difference)) matched++;
            else differences++;
        }
        for (BillingRequests.ChannelTransaction transaction : channel.values()) {
            upsertReconciliation(
                    request.date(),
                    provider,
                    transaction.paymentNo(),
                    transaction.providerTransactionId(),
                    null,
                    transaction.amountMinor(),
                    null,
                    normalize(transaction.status()),
                    "CHANNEL_ONLY"
            );
            differences++;
        }
        metrics.reconciliationDifferences(differences);
        return Map.of("matched", matched, "differences", differences);
    }

    @Override
    @Transactional
    public Map<String, Object> resolve(
            long id,
            long operatorUserId,
            BillingRequests.ResolveReconciliation request
    ) {
        LocalDateTime resolvedAt = LocalDateTime.now();
        int updated = reconciliationItemMapper.resolveItem(
                id,
                operatorUserId,
                safe(request.note(), 512),
                resolvedAt
        );
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return reconciliationItemMapper.selectMapById(id);
    }

    private void upsertReconciliation(
            LocalDate date,
            String provider,
            String paymentNo,
            String providerTransactionId,
            Long internalAmount,
            Long providerAmount,
            String internalStatus,
            String providerStatus,
            String difference
    ) {
        LocalDateTime now = LocalDateTime.now();
        reconciliationItemMapper.upsertItem(
                nextId(),
                date,
                provider,
                safeNullable(paymentNo, 64),
                safeNullable(providerTransactionId, 128),
                internalAmount,
                providerAmount,
                safeNullable(internalStatus, 32),
                safeNullable(providerStatus, 32),
                difference,
                "MATCHED".equals(difference) ? "AUTO_RESOLVED" : "OPEN",
                now,
                now
        );
    }

    private static long number(Object value) {
        if (value == null) return 0;
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeNullable(String value) {
        return StringUtils.hasText(value) ? normalize(value) : null;
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

    private static void require(boolean enabled) {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
