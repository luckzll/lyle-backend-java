package com.example.aiapp.service.impl;

import com.example.aiapp.billing.BillingMetrics;
import com.example.aiapp.billing.BillingProperties;
import com.example.aiapp.billing.BillingRequests;
import com.example.aiapp.billing.payment.PaymentGateway;
import com.example.aiapp.billing.payment.WechatPayPaymentGateway;
import com.example.aiapp.domain.entity.BillingOrderEntity;
import com.example.aiapp.mapper.BillingOrderMapper;
import com.example.aiapp.service.BillingOrderQueryService;
import com.example.aiapp.service.BillingPaymentService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static com.example.aiapp.billing.support.BillingIdentifiers.businessNo;
import static com.example.aiapp.billing.support.BillingIdentifiers.nextId;

/**
 * 支付下单与回调处理服务实现。
 */
@Service
public class BillingPaymentServiceImpl implements BillingPaymentService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final BillingOrderMapper billingOrderMapper;
    private final ObjectMapper objectMapper;
    private final BillingProperties properties;
    private final PaymentGateway paymentGateway;
    private final BillingMetrics metrics;
    private final BillingOrderQueryService orderQueryService;

    public BillingPaymentServiceImpl(
            BillingOrderMapper billingOrderMapper,
            ObjectMapper objectMapper,
            BillingProperties properties,
            PaymentGateway paymentGateway,
            BillingMetrics metrics,
            BillingOrderQueryService orderQueryService
    ) {
        this.billingOrderMapper = billingOrderMapper;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.paymentGateway = paymentGateway;
        this.metrics = metrics;
        this.orderQueryService = orderQueryService;
    }

    @Override
    @Transactional
    public Map<String, Object> createOrder(long userId, BillingRequests.CreateOrder request) {
        require(properties.catalogEnabled() && properties.userViewEnabled());
        String clientRequestId = safe(request.clientRequestId(), 128);
        String existingOrderNo = billingOrderMapper.selectOrderNoByClientRequest(
                userId,
                clientRequestId,
                false
        );
        if (StringUtils.hasText(existingOrderNo)) {
            metrics.duplicatePrevented();
            return orderQueryService.getOrder(userId, existingOrderNo);
        }

        Map<String, Object> price = requireSellablePrice(request.priceVersionCode());
        String productType = normalize(String.valueOf(price.get("product_type")));
        String intent = normalize(request.purchaseIntent());
        if (!List.of("NEW_PURCHASE", "RENEWAL", "ADD_ON").contains(intent)) {
            throw badRequest("不支持的购买类型。");
        }
        if ("ADD_ON".equals(intent) != "CREDIT_PACK".equals(productType)) {
            throw badRequest("购买类型与商品不匹配。");
        }
        if ("PLAN".equals(productType)) {
            Map<String, Object> activePlan = currentPlan(userId);
            String activeCode = normalize(String.valueOf(activePlan.get("code")));
            String targetCode = normalize(String.valueOf(price.get("product_code")));
            if (!"FREE".equals(activeCode) && "NEW_PURCHASE".equals(intent)) {
                throw conflict("当前已有生效主套餐，请使用续期。");
            }
            if (!"FREE".equals(activeCode)
                    && "RENEWAL".equals(intent)
                    && !activeCode.equals(targetCode)) {
                throw conflict("P0 暂不支持套餐切换或差价升级。");
            }
        }
        if (!normalize(properties.currency()).equals(normalize(String.valueOf(price.get("currency"))))) {
            throw badRequest("价格币种与当前市场不一致。");
        }

        long accountId = number(ensureAccount(userId).get("id"));
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("productCode", price.get("product_code"));
        snapshot.put("productType", productType);
        snapshot.put("name", price.get("display_name"));
        snapshot.put("description", price.get("description"));
        snapshot.put("priceVersionCode", price.get("version_code"));
        snapshot.put("includedCreditMicros", number(price.get("included_credit_micros")));
        snapshot.put("validityDays", price.get("validity_days"));
        snapshot.put("benefits", benefits(price.get("entitlement_snapshot")));
        snapshot.put("entitlements", jsonMap(price.get("entitlement_snapshot")));
        snapshot.put("pricingRules", jsonMap(price.get("pricing_rule_snapshot")));

        long amountMinor = number(price.get("amount_minor"));
        if (amountMinor <= 0) {
            throw badRequest("免费套餐无需创建支付订单。");
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusMinutes(Math.max(5, properties.orderTtlMinutes()));
        String orderNo = businessNo("L");
        try {
            BillingOrderEntity entity = new BillingOrderEntity();
            entity.setId(nextId());
            entity.setOrderNo(orderNo);
            entity.setUserId(userId);
            entity.setAccountId(accountId);
            entity.setOrderType(intent);
            entity.setStatus("CREATED");
            entity.setProductCode(String.valueOf(price.get("product_code")));
            entity.setPriceVersionCode(String.valueOf(price.get("version_code")));
            entity.setProductSnapshot(json(snapshot));
            entity.setCurrency(normalize(String.valueOf(price.get("currency"))));
            entity.setOriginalAmountMinor(amountMinor);
            entity.setDiscountAmountMinor(0L);
            entity.setPayableAmountMinor(amountMinor);
            entity.setPaidAmountMinor(0L);
            entity.setClientRequestId(clientRequestId);
            entity.setExpiresAt(expiresAt);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            billingOrderMapper.insert(entity);
        } catch (DataAccessException exception) {
            String racedOrderNo = billingOrderMapper.selectOrderNoByClientRequest(
                    userId,
                    clientRequestId,
                    true
            );
            if (StringUtils.hasText(racedOrderNo)) {
                metrics.duplicatePrevented();
                return orderQueryService.getOrder(userId, racedOrderNo);
            }
            throw exception;
        }
        metrics.orderCreated();
        return orderQueryService.getOrder(userId, orderNo);
    }

    @Override
    @Transactional
    public Map<String, Object> refreshOrderStatus(long userId, String orderNo) {
        require(properties.userViewEnabled());
        Map<String, Object> order = scopedOrder(userId, safe(orderNo, 64), true);
        if (!List.of("CREATED", "PENDING_PAYMENT")
                .contains(normalize(String.valueOf(order.get("status"))))) {
            return orderResponse(order);
        }
        Map<String, Object> payment = currentPayment(number(order.get("id")));
        if (payment == null) {
            return orderResponse(order);
        }
        PaymentGateway.PaymentResult result =
                paymentGateway.queryPayment(String.valueOf(payment.get("payment_no")));
        String providerStatus = normalize(result.status());
        if ("SUCCEEDED".equals(providerStatus)) {
            confirmPaymentInternal(
                    String.valueOf(payment.get("payment_no")),
                    result.providerTransactionId(),
                    number(payment.get("amount_minor")),
                    String.valueOf(payment.get("currency")),
                    String.valueOf(payment.get("provider"))
            );
        } else if ("CLOSED".equals(providerStatus)) {
            markPaymentAndOrderClosed(payment);
        }
        return orderQueryService.getOrder(userId, orderNo);
    }

    @Override
    @Transactional
    public Map<String, Object> checkout(long userId, String orderNo) {
        require(properties.checkoutEnabled() && properties.userViewEnabled());
        if (!normalize(properties.paymentProvider()).equals(paymentGateway.provider())) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "当前支付渠道尚未配置完成。"
            );
        }
        Map<String, Object> order = scopedOrder(userId, safe(orderNo, 64), true);
        String status = normalize(String.valueOf(order.get("status")));
        if (List.of("PAID", "FULFILLING", "FULFILLED", "REFUNDING", "REFUNDED").contains(status)) {
            return checkoutResponse(order, currentPayment(number(order.get("id"))), null);
        }
        if (!List.of("CREATED", "PENDING_PAYMENT").contains(status)) {
            throw conflict("该订单当前不能支付。");
        }
        LocalDateTime orderExpiresAt = localDateTime(order.get("expires_at"));
        if (orderExpiresAt == null || !orderExpiresAt.isAfter(LocalDateTime.now())) {
            closeExpiredOrder(number(order.get("id")));
            throw conflict("订单已关闭，请重新选择套餐。");
        }
        Map<String, Object> existing = currentPayment(number(order.get("id")));
        if (existing != null
                && "PENDING".equals(normalize(String.valueOf(existing.get("status"))))
                && localDateTime(existing.get("expires_at")).isAfter(LocalDateTime.now())) {
            PaymentGateway.CheckoutResult restored = paymentGateway.createPayment(
                    createPaymentCommand(
                            order,
                            String.valueOf(existing.get("payment_no")),
                            localDateTime(existing.get("expires_at"))
                    )
            );
            return checkoutResponse(order, existing, restored);
        }

        String paymentNo = businessNo("P");
        PaymentGateway.CheckoutResult result = paymentGateway.createPayment(
                createPaymentCommand(order, paymentNo, orderExpiresAt)
        );
        LocalDateTime now = LocalDateTime.now();
        billingOrderMapper.insertPaymentAttempt(
                nextId(),
                paymentNo,
                number(order.get("id")),
                result.provider(),
                String.valueOf(order.get("currency")),
                number(order.get("payable_amount_minor")),
                result.expiresAt(),
                now
        );
        billingOrderMapper.markOrderPendingPayment(number(order.get("id")), now);
        order.put("status", "PENDING_PAYMENT");
        return checkoutResponse(order, currentPayment(number(order.get("id"))), result);
    }

    @Override
    @Transactional
    public Map<String, Object> closeOrder(long userId, String orderNo) {
        Map<String, Object> order = scopedOrder(userId, safe(orderNo, 64), true);
        String status = normalize(String.valueOf(order.get("status")));
        if (!List.of("CREATED", "PENDING_PAYMENT").contains(status)) {
            return orderQueryService.getOrder(userId, orderNo);
        }
        Map<String, Object> payment = currentPayment(number(order.get("id")));
        if (payment != null) {
            PaymentGateway.PaymentResult result =
                    paymentGateway.queryPayment(String.valueOf(payment.get("payment_no")));
            if ("SUCCEEDED".equals(normalize(result.status()))) {
                confirmPaymentInternal(
                        String.valueOf(payment.get("payment_no")),
                        result.providerTransactionId(),
                        number(payment.get("amount_minor")),
                        String.valueOf(payment.get("currency")),
                        paymentGateway.provider()
                );
                return orderQueryService.getOrder(userId, orderNo);
            }
            if (!"PENDING".equals(normalize(result.status()))) {
                throw conflict("支付状态暂时无法确认，请稍后重试。");
            }
            PaymentGateway.PaymentResult closeResult =
                    paymentGateway.closePayment(String.valueOf(payment.get("payment_no")));
            if (!"CLOSED".equals(normalize(closeResult.status()))) {
                throw conflict("支付渠道暂未确认关单，请稍后重试。");
            }
            markPaymentAndOrderClosed(payment);
        } else {
            closeExpiredOrder(number(order.get("id")));
        }
        return orderQueryService.getOrder(userId, orderNo);
    }

    @Override
    @Transactional
    public void deleteOrder(long userId, String orderNo) {
        Map<String, Object> order = scopedOrder(userId, safe(orderNo, 64), true);
        String status = normalize(String.valueOf(order.get("status")));
        if (!List.of("CLOSED", "CANCELLED").contains(status)) {
            throw conflict("请先取消待支付订单；已支付订单不能删除。");
        }
        int updated = billingOrderMapper.markOrderHiddenForUser(
                number(order.get("id")),
                userId,
                LocalDateTime.now()
        );
        if (updated != 1) {
            throw conflict("订单记录已经删除或状态已发生变化。");
        }
    }

    @Override
    @Transactional
    public Map<String, Object> confirmMockPayment(String paymentNo) {
        if (!"MOCK".equals(paymentGateway.provider())) {
            throw badRequest("当前环境不是沙箱支付。");
        }
        Map<String, Object> payment = requirePayment(paymentNo, true);
        confirmPaymentInternal(
                paymentNo,
                "mock-" + UUID.randomUUID(),
                number(payment.get("amount_minor")),
                String.valueOf(payment.get("currency")),
                paymentGateway.provider()
        );
        return paymentStatus(paymentNo);
    }

    @Override
    @Transactional
    public void confirmPayment(
            String paymentNo,
            String providerTransactionId,
            long amountMinor,
            String currency,
            String provider
    ) {
        confirmPaymentInternal(paymentNo, providerTransactionId, amountMinor, currency, provider);
    }

    private void confirmPaymentInternal(
            String paymentNo,
            String providerTransactionId,
            long amountMinor,
            String currency,
            String provider
    ) {
        Map<String, Object> payment = requirePayment(paymentNo, true);
        String paymentStatus = normalize(String.valueOf(payment.get("status")));
        if ("SUCCEEDED".equals(paymentStatus)) {
            return;
        }
        if (!List.of("INITIATED", "PENDING", "CLOSED").contains(paymentStatus)) {
            throw conflict("支付单已关闭。");
        }
        if (!normalize(String.valueOf(payment.get("provider"))).equals(normalize(provider))
                || number(payment.get("amount_minor")) != amountMinor
                || !normalize(String.valueOf(payment.get("currency"))).equals(normalize(currency))) {
            throw badRequest("支付通知的金额、币种或渠道不匹配。");
        }
        String orderStatus = normalize(String.valueOf(payment.get("order_status")));
        if (!List.of("CREATED", "PENDING_PAYMENT", "CLOSED", "PAID", "FULFILLING")
                .contains(orderStatus)) {
            throw conflict("订单状态不允许确认付款。");
        }
        LocalDateTime now = LocalDateTime.now();
        billingOrderMapper.markPaymentSucceeded(
                number(payment.get("id")),
                safe(providerTransactionId, 128),
                now
        );
        billingOrderMapper.markOrderPaid(number(payment.get("order_id")), now);
        metrics.orderPaid();
        enqueue(
                "ORDER",
                String.valueOf(payment.get("order_no")),
                "FULFILL_ORDER",
                1,
                Map.of("paymentNo", paymentNo)
        );
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> paymentStatus(String paymentNo) {
        Map<String, Object> payment = requirePayment(paymentNo, false);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("paymentNo", payment.get("payment_no"));
        response.put("orderNo", payment.get("order_no"));
        response.put("paymentStatus", payment.get("status"));
        response.put("orderStatus", payment.get("order_status"));
        return response;
    }

    @Override
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public Map<String, Object> processWebhook(
            String provider,
            byte[] rawBody,
            String signature
    ) {
        require(properties.webhookEnabled());
        String normalizedProvider = normalize(provider);
        String payloadHash = sha256(rawBody);
        if (!normalizedProvider.equals(normalize(properties.paymentProvider()))
                || !verifyHmac(rawBody, signature)) {
            metrics.invalidCallback();
            recordWebhook(
                    normalizedProvider,
                    "invalid-" + payloadHash.substring(0, 24),
                    payloadHash,
                    false,
                    "REJECTED",
                    null,
                    "INVALID_SIGNATURE"
            );
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        Map<String, Object> payload;
        try {
            payload = objectMapper.readValue(rawBody, MAP_TYPE);
        } catch (Exception exception) {
            throw badRequest("支付通知格式无效。");
        }
        String eventId = safe(String.valueOf(payload.get("providerEventId")), 128);
        String paymentNo = safe(String.valueOf(payload.get("paymentNo")), 64);
        int inserted = recordWebhook(
                normalizedProvider,
                eventId,
                payloadHash,
                true,
                "RECEIVED",
                paymentNo,
                null
        );
        if (inserted == 0) {
            metrics.duplicatePrevented();
            return Map.of("status", "accepted", "duplicate", true);
        }
        try {
            if (!"SUCCEEDED".equals(normalize(String.valueOf(payload.get("status"))))) {
                throw badRequest("支付通知不是成功状态。");
            }
            if (StringUtils.hasText(properties.merchantId())
                    && !properties.merchantId().equals(String.valueOf(payload.get("merchantId")))) {
                throw badRequest("商户号不匹配。");
            }
            confirmPaymentInternal(
                    paymentNo,
                    safe(String.valueOf(payload.get("providerTransactionId")), 128),
                    number(payload.get("amountMinor")),
                    String.valueOf(payload.get("currency")),
                    normalizedProvider
            );
            billingOrderMapper.updateWebhookProcessed(
                    normalizedProvider,
                    eventId,
                    LocalDateTime.now()
            );
            return Map.of("status", "accepted", "duplicate", false);
        } catch (RuntimeException exception) {
            billingOrderMapper.updateWebhookFailed(
                    normalizedProvider,
                    eventId,
                    LocalDateTime.now(),
                    "VALIDATION_FAILED"
            );
            throw exception;
        }
    }

    @Override
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public boolean processAlipayNotification(Map<String, String> parameters) {
        require(properties.webhookEnabled());
        if (!"ALIPAY".equals(normalize(properties.paymentProvider()))
                || !"ALIPAY".equals(paymentGateway.provider())) {
            throw badRequest("当前环境未启用支付宝通知。");
        }
        String eventId = requiredNotificationField(parameters, "notify_id", 128);
        String paymentNo = requiredNotificationField(parameters, "out_trade_no", 64);
        String payloadHash;
        try {
            payloadHash = sha256(objectMapper.writeValueAsBytes(new TreeMap<>(parameters)));
        } catch (Exception exception) {
            throw badRequest("支付宝通知格式无效。");
        }
        Map<String, Object> payment = requirePayment(paymentNo, true);
        long amountMinor = alipayAmountMinor(parameters.get("total_amount"));
        if (!"ALIPAY".equals(normalize(String.valueOf(payment.get("provider"))))
                || !"CNY".equals(normalize(String.valueOf(payment.get("currency"))))
                || number(payment.get("amount_minor")) != amountMinor) {
            throw badRequest("支付宝通知的订单、金额或币种不匹配。");
        }
        int inserted = recordWebhook(
                "ALIPAY",
                eventId,
                payloadHash,
                true,
                "RECEIVED",
                paymentNo,
                null
        );
        if (inserted == 0) {
            metrics.duplicatePrevented();
            return true;
        }
        try {
            if (isPaidAlipayNotification(parameters)) {
                String tradeNo = requiredNotificationField(parameters, "trade_no", 128);
                confirmPaymentInternal(paymentNo, tradeNo, amountMinor, "CNY", "ALIPAY");
            } else if ("TRADE_CLOSED".equals(parameters.get("trade_status"))) {
                markPaymentAndOrderClosed(payment);
            }
            billingOrderMapper.updateWebhookProcessed("ALIPAY", eventId, LocalDateTime.now());
            return true;
        } catch (RuntimeException exception) {
            billingOrderMapper.updateWebhookFailed(
                    "ALIPAY",
                    eventId,
                    LocalDateTime.now(),
                    "VALIDATION_FAILED"
            );
            throw exception;
        }
    }

    @Override
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public boolean processWechatPayNotification(
            WechatPayPaymentGateway.VerifiedPaymentNotification notification
    ) {
        require(properties.webhookEnabled());
        if (!"WECHAT_PAY".equals(normalize(properties.paymentProvider()))
                || !"WECHAT_PAY".equals(paymentGateway.provider())) {
            throw badRequest("当前环境未启用微信支付通知。");
        }
        Map<String, Object> payment = requirePayment(notification.paymentNo(), true);
        if (!"WECHAT_PAY".equals(normalize(String.valueOf(payment.get("provider"))))
                || !normalize(notification.currency())
                .equals(normalize(String.valueOf(payment.get("currency"))))
                || number(payment.get("amount_minor")) != notification.amountMinor()) {
            throw badRequest("微信支付通知的订单、金额或币种不匹配。");
        }
        int inserted = recordWebhook(
                "WECHAT_PAY",
                notification.eventId(),
                notification.payloadHash(),
                true,
                "RECEIVED",
                notification.paymentNo(),
                null
        );
        if (inserted == 0) {
            metrics.duplicatePrevented();
            return true;
        }
        try {
            String status = normalize(notification.status());
            if ("SUCCEEDED".equals(status)) {
                if (!StringUtils.hasText(notification.providerTransactionId())) {
                    throw badRequest("微信支付通知缺少平台交易号。");
                }
                confirmPaymentInternal(
                        notification.paymentNo(),
                        notification.providerTransactionId(),
                        notification.amountMinor(),
                        notification.currency(),
                        "WECHAT_PAY"
                );
            } else if ("CLOSED".equals(status)) {
                markPaymentAndOrderClosed(payment);
            }
            billingOrderMapper.updateWebhookProcessed(
                    "WECHAT_PAY",
                    notification.eventId(),
                    LocalDateTime.now()
            );
            return true;
        } catch (RuntimeException exception) {
            billingOrderMapper.updateWebhookFailed(
                    "WECHAT_PAY",
                    notification.eventId(),
                    LocalDateTime.now(),
                    "VALIDATION_FAILED"
            );
            throw exception;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> expiredPendingOrderNumbers(int limit) {
        if (!properties.checkoutEnabled()) {
            return List.of();
        }
        return billingOrderMapper.selectExpiredPendingOrderNumbers(
                Math.max(1, Math.min(limit, 100))
        );
    }

    @Override
    @Transactional
    public void closeExpiredPendingOrder(String orderNo) {
        Map<String, Object> order = requireOrder(orderNo, true);
        if (!List.of("CREATED", "PENDING_PAYMENT")
                .contains(normalize(String.valueOf(order.get("status"))))) {
            return;
        }
        Map<String, Object> payment = currentPayment(number(order.get("id")));
        if (payment != null) {
            PaymentGateway.PaymentResult result =
                    paymentGateway.queryPayment(String.valueOf(payment.get("payment_no")));
            if ("SUCCEEDED".equals(normalize(result.status()))) {
                confirmPaymentInternal(
                        String.valueOf(payment.get("payment_no")),
                        result.providerTransactionId(),
                        number(payment.get("amount_minor")),
                        String.valueOf(payment.get("currency")),
                        String.valueOf(payment.get("provider"))
                );
                return;
            }
            if (!"PENDING".equals(normalize(result.status()))) {
                return;
            }
            PaymentGateway.PaymentResult closeResult =
                    paymentGateway.closePayment(String.valueOf(payment.get("payment_no")));
            if (!"CLOSED".equals(normalize(closeResult.status()))) {
                return;
            }
            markPaymentAndOrderClosed(payment);
        } else {
            closeExpiredOrder(number(order.get("id")));
        }
    }

    private Map<String, Object> ensureAccount(long userId) {
        Map<String, Object> existing = billingOrderMapper.selectAccountByUserId(userId, false);
        if (existing != null) {
            return existing;
        }
        LocalDateTime now = LocalDateTime.now();
        billingOrderMapper.insertAccountIgnore(nextId(), userId, now);
        return billingOrderMapper.selectAccountByUserId(userId, true);
    }

    private Map<String, Object> currentPlan(long userId) {
        Map<String, Object> row = billingOrderMapper.selectCurrentPlan(userId);
        if (row == null) {
            return Map.of(
                    "code", "FREE",
                    "name", "免费版",
                    "status", "ACTIVE",
                    "benefits", List.of("基础聊天、记忆与免费 AI 额度")
            );
        }
        Map<String, Object> snapshot = jsonMap(row.get("product_snapshot"));
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("code", snapshot.get("productCode"));
        plan.put("name", snapshot.get("name"));
        plan.put("status", "ACTIVE");
        plan.put("benefits", snapshot.getOrDefault("benefits", List.of()));
        plan.put("validFrom", row.get("valid_from"));
        plan.put("expiresAt", row.get("expires_at"));
        plan.put("orderNo", row.get("order_no"));
        return plan;
    }

    private Map<String, Object> requireSellablePrice(String versionCode) {
        Map<String, Object> row = billingOrderMapper.selectSellablePrice(
                normalizeCode(versionCode, 64)
        );
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "价格已下架或暂不可售。");
        }
        return row;
    }

    private Map<String, Object> scopedOrder(long userId, String orderNo, boolean forUpdate) {
        Map<String, Object> row = billingOrderMapper.selectOrderForUser(
                userId,
                orderNo,
                forUpdate
        );
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return row;
    }

    private Map<String, Object> requireOrder(String orderNo, boolean forUpdate) {
        Map<String, Object> row = billingOrderMapper.selectOrderMapByOrderNo(
                safe(orderNo, 64),
                forUpdate
        );
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return row;
    }

    private Map<String, Object> requirePayment(String paymentNo, boolean forUpdate) {
        Map<String, Object> row = billingOrderMapper.selectPaymentByPaymentNo(
                safe(paymentNo, 64),
                forUpdate
        );
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return row;
    }

    private Map<String, Object> currentPayment(long orderId) {
        return billingOrderMapper.selectLatestPaymentByOrderId(orderId);
    }

    private Map<String, Object> checkoutResponse(
            Map<String, Object> order,
            Map<String, Object> payment,
            PaymentGateway.CheckoutResult checkout
    ) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("orderNo", order.get("order_no"));
        response.put("orderStatus", order.get("status"));
        response.put("paymentNo", payment == null ? null : payment.get("payment_no"));
        response.put("paymentStatus", payment == null ? null : payment.get("status"));
        response.put("provider", payment == null ? paymentGateway.provider() : payment.get("provider"));
        response.put("checkoutType", checkout == null ? "RESTORE" : checkout.checkoutType());
        response.put("action", checkout == null ? null : checkout.action());
        response.put("expiresAt", payment == null ? order.get("expires_at") : payment.get("expires_at"));
        response.put("sandbox", "MOCK".equals(paymentGateway.provider()));
        return response;
    }

    private PaymentGateway.CreatePaymentCommand createPaymentCommand(
            Map<String, Object> order,
            String paymentNo,
            LocalDateTime expiresAt
    ) {
        return new PaymentGateway.CreatePaymentCommand(
                paymentNo,
                String.valueOf(order.get("order_no")),
                number(order.get("payable_amount_minor")),
                String.valueOf(order.get("currency")),
                snapshotName(order.get("product_snapshot")),
                expiresAt
        );
    }

    private void markPaymentAndOrderClosed(Map<String, Object> payment) {
        LocalDateTime now = LocalDateTime.now();
        billingOrderMapper.markPaymentClosed(number(payment.get("id")), now);
        billingOrderMapper.markOrderClosed(number(payment.get("order_id")), now);
    }

    private Map<String, Object> orderResponse(Map<String, Object> row) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("orderNo", row.get("order_no"));
        response.put("status", row.get("status"));
        response.put("orderType", row.get("order_type"));
        response.put("productCode", row.get("product_code"));
        response.put("priceVersionCode", row.get("price_version_code"));
        response.put("product", jsonMap(row.get("product_snapshot")));
        response.put("amount", amount(
                String.valueOf(row.get("currency")),
                number(row.get("payable_amount_minor")),
                number(row.get("paid_amount_minor"))
        ));
        response.put("paymentNo", row.get("payment_no"));
        response.put("paymentStatus", row.get("payment_status"));
        response.put("paymentProvider", row.get("payment_provider"));
        response.put("refundStatus", row.get("refund_status"));
        response.put("refundSucceededAt", row.get("refund_succeeded_at"));
        response.put("expiresAt", row.get("expires_at"));
        response.put("paidAt", row.get("paid_at"));
        response.put("fulfilledAt", row.get("fulfilled_at"));
        response.put("createdAt", row.get("created_at"));
        return response;
    }

    private void enqueue(
            String aggregateType,
            String aggregateId,
            String eventType,
            int version,
            Map<String, Object> payload
    ) {
        LocalDateTime now = LocalDateTime.now();
        billingOrderMapper.insertOutbox(
                nextId(),
                normalize(aggregateType),
                safe(aggregateId, 128),
                normalize(eventType),
                version,
                json(payload),
                now
        );
    }

    private int recordWebhook(
            String provider,
            String eventId,
            String payloadHash,
            boolean valid,
            String status,
            String paymentNo,
            String error
    ) {
        return billingOrderMapper.insertWebhook(
                nextId(),
                safe(provider, 32),
                safe(eventId, 128),
                payloadHash,
                valid,
                status,
                safeNullable(paymentNo, 64),
                LocalDateTime.now(),
                error
        );
    }

    private void closeExpiredOrder(long orderId) {
        LocalDateTime now = LocalDateTime.now();
        billingOrderMapper.markOrderClosed(orderId, now);
        billingOrderMapper.closePaymentsByOrder(orderId, now);
    }

    private Map<String, Object> amount(String currency, long payable, long paid) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("currency", normalize(currency));
        response.put("payableMinor", payable);
        response.put("paidMinor", paid);
        response.put("payable", formatAmount(currency, payable));
        response.put("paid", formatAmount(currency, paid));
        return response;
    }

    private String formatAmount(String currency, long amountMinor) {
        return normalize(currency) + " " + BigDecimal.valueOf(amountMinor, 2)
                .setScale(2, RoundingMode.UNNECESSARY)
                .toPlainString();
    }

    private String snapshotName(Object value) {
        Object name = jsonMap(value).get("name");
        return name == null ? "Lyle AI 商业套餐" : String.valueOf(name);
    }

    private List<?> benefits(Object value) {
        Object benefits = jsonMap(value).get("benefits");
        return benefits instanceof List<?> list ? list : List.of();
    }

    private Map<String, Object> jsonMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        try {
            return objectMapper.readValue(String.valueOf(value), MAP_TYPE);
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static boolean isPaidAlipayNotification(Map<String, String> parameters) {
        String tradeStatus = parameters.get("trade_status");
        return "TRADE_SUCCESS".equals(tradeStatus) || "TRADE_FINISHED".equals(tradeStatus);
    }

    private static String requiredNotificationField(
            Map<String, String> parameters,
            String name,
            int maxLength
    ) {
        return safe(parameters.get(name), maxLength);
    }

    private static long alipayAmountMinor(String value) {
        try {
            return new BigDecimal(safe(value, 32))
                    .movePointRight(2)
                    .setScale(0, RoundingMode.UNNECESSARY)
                    .longValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw badRequest("支付宝通知金额格式无效。");
        }
    }

    private boolean verifyHmac(byte[] body, String signature) {
        if (!StringUtils.hasText(properties.webhookSecret()) || !StringUtils.hasText(signature)) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    properties.webhookSecret().getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"
            ));
            byte[] expected = mac.doFinal(body);
            byte[] actual = HexFormat.of().parseHex(signature.trim());
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception exception) {
            return false;
        }
    }

    private static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static long number(Object value) {
        if (value == null) return 0;
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private static LocalDateTime localDateTime(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime dateTime) return dateTime;
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toLocalDateTime();
        if (value instanceof java.time.OffsetDateTime offsetDateTime) return offsetDateTime.toLocalDateTime();
        return LocalDateTime.parse(String.valueOf(value).replace(" ", "T"));
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

    private static void require(boolean enabled) {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
