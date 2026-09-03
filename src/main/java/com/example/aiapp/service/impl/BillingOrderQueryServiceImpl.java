package com.example.aiapp.service.impl;

import com.example.aiapp.mapper.BillingOrderMapper;
import com.example.aiapp.mapper.RefundOrderMapper;
import com.example.aiapp.domain.vo.BillingOrderReadVO;
import com.example.aiapp.domain.vo.BillingRefundReadVO;
import com.example.aiapp.service.BillingOrderQueryService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 计费订单查询服务实现。
 */
@Service
public class BillingOrderQueryServiceImpl implements BillingOrderQueryService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final BillingOrderMapper orderMapper;
    private final RefundOrderMapper refundMapper;
    private final ObjectMapper objectMapper;

    public BillingOrderQueryServiceImpl(
            BillingOrderMapper orderMapper,
            RefundOrderMapper refundMapper,
            ObjectMapper objectMapper
    ) {
        this.orderMapper = orderMapper;
        this.refundMapper = refundMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listOrders(long userId, int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        return orderMapper.selectUserOrders(userId, boundedLimit).stream()
                .map(this::orderResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getOrder(long userId, String orderNo) {
        BillingOrderReadVO order = orderMapper.selectUserOrder(userId, safe(orderNo, 64));
        if (order == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return orderResponse(order);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listRefunds(long userId) {
        return refundMapper.selectUserRefunds(userId).stream()
                .map(this::refundResponse)
                .toList();
    }

    private Map<String, Object> orderResponse(BillingOrderReadVO row) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("orderNo", row.getOrderNo());
        response.put("status", row.getStatus());
        response.put("orderType", row.getOrderType());
        response.put("productCode", row.getProductCode());
        response.put("priceVersionCode", row.getPriceVersionCode());
        response.put("product", jsonMap(row.getProductSnapshot()));
        response.put("amount", amount(
                row.getCurrency(),
                number(row.getPayableAmountMinor()),
                number(row.getPaidAmountMinor())
        ));
        response.put("paymentNo", row.getPaymentNo());
        response.put("paymentStatus", row.getPaymentStatus());
        response.put("paymentProvider", row.getPaymentProvider());
        response.put("refundStatus", row.getRefundStatus());
        response.put("refundSucceededAt", row.getRefundSucceededAt());
        response.put("expiresAt", row.getExpiresAt());
        response.put("paidAt", row.getPaidAt());
        response.put("fulfilledAt", row.getFulfilledAt());
        response.put("createdAt", row.getCreatedAt());
        return response;
    }

    private Map<String, Object> refundResponse(BillingRefundReadVO row) {
        long amountMinor = number(row.getAmountMinor());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("refundNo", row.getRefundNo());
        response.put("orderNo", row.getOrderNo());
        response.put("status", row.getStatus());
        response.put("amount", amount(row.getCurrency(), amountMinor, amountMinor));
        response.put("reasonCode", row.getReasonCode());
        response.put("reasonNote", row.getReasonNote());
        response.put("product", jsonMap(row.getProductSnapshot()));
        response.put("succeededAt", row.getSucceededAt());
        response.put("createdAt", row.getCreatedAt());
        return response;
    }

    private Map<String, Object> amount(String currency, long payable, long paid) {
        String normalizedCurrency = normalizeCurrency(currency);
        BigDecimal value = BigDecimal.valueOf(payable, 2);
        String symbol = "CNY".equals(normalizedCurrency) ? "¥" : normalizedCurrency + " ";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("currency", normalizedCurrency);
        result.put("payableMinor", payable);
        result.put("paidMinor", paid);
        result.put("formatted", symbol + value.setScale(2, RoundingMode.UNNECESSARY).toPlainString());
        return result;
    }

    private Map<String, Object> jsonMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        if (value == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(String.valueOf(value), MAP_TYPE);
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private static String normalizeCurrency(String value) {
        String result = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!result.matches("[A-Z]{3}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "币种代码无效。");
        }
        return result;
    }

    private static String safe(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必填字段不能为空。");
        }
        String result = value.trim();
        if (result.length() > maxLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "字段长度超出限制。");
        }
        return result;
    }
}
