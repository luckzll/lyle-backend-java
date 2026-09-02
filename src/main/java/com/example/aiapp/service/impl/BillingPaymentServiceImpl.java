package com.example.aiapp.service.impl;

import com.example.aiapp.billing.BillingMetrics;
import com.example.aiapp.billing.BillingProperties;
import com.example.aiapp.billing.BillingRequests;
import com.example.aiapp.billing.payment.PaymentGateway;
import com.example.aiapp.billing.payment.WechatPayPaymentGateway;
import com.example.aiapp.service.BillingOrderQueryService;
import com.example.aiapp.service.BillingPaymentService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.sql.ResultSet;
import java.sql.SQLException;
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

@Service
public class BillingPaymentServiceImpl implements BillingPaymentService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final BillingProperties properties;
    private final PaymentGateway paymentGateway;
    private final BillingMetrics metrics;
    private final BillingOrderQueryService orderQueryService;

    public BillingPaymentServiceImpl(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            BillingProperties properties,
            PaymentGateway paymentGateway,
            BillingMetrics metrics,
            BillingOrderQueryService orderQueryService
    ) {
        this.jdbcTemplate = jdbcTemplate;
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
        List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                "SELECT order_no FROM billing_order WHERE user_id = ? AND client_request_id = ?",
                userId,
                clientRequestId
        );
        if (!existing.isEmpty()) {
            metrics.duplicatePrevented();
            return orderQueryService.getOrder(userId, String.valueOf(existing.get(0).get("order_no")));
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
            jdbcTemplate.update(
                    """
                    INSERT INTO billing_order (
                        id, order_no, user_id, account_id, order_type, status,
                        product_code, price_version_code, product_snapshot, currency,
                        original_amount_minor, discount_amount_minor, payable_amount_minor,
                        paid_amount_minor, client_request_id, expires_at, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, 'CREATED', ?, ?, ?, ?, ?, 0, ?, 0, ?, ?, ?, ?)
                    """,
                    nextId(),
                    orderNo,
                    userId,
                    accountId,
                    intent,
                    price.get("product_code"),
                    price.get("version_code"),
                    json(snapshot),
                    normalize(String.valueOf(price.get("currency"))),
                    amountMinor,
                    amountMinor,
                    clientRequestId,
                    expiresAt,
                    now,
                    now
            );
        } catch (DataAccessException exception) {
            List<Map<String, Object>> raced = jdbcTemplate.queryForList(
                    """
                    SELECT order_no
                    FROM billing_order
                    WHERE user_id = ? AND client_request_id = ?
                    FOR UPDATE
                    """,
                    userId,
                    clientRequestId
            );
            if (!raced.isEmpty()) {
                metrics.duplicatePrevented();
                return orderQueryService.getOrder(
                        userId,
                        String.valueOf(raced.get(0).get("order_no"))
                );
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
        jdbcTemplate.update(
                """
                INSERT INTO payment_attempt (
                    id, payment_no, order_id, provider, status, currency, amount_minor,
                    expires_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?)
                """,
                nextId(),
                paymentNo,
                order.get("id"),
                result.provider(),
                order.get("currency"),
                order.get("payable_amount_minor"),
                result.expiresAt(),
                now,
                now
        );
        jdbcTemplate.update(
                "UPDATE billing_order SET status = 'PENDING_PAYMENT', updated_at = ? WHERE id = ?",
                now,
                order.get("id")
        );
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
        jdbcTemplate.update(
                """
                UPDATE payment_attempt
                SET status = 'SUCCEEDED', provider_transaction_id = ?,
                    succeeded_at = ?, updated_at = ?
                WHERE id = ?
                """,
                safe(providerTransactionId, 128),
                now,
                now,
                payment.get("id")
        );
        jdbcTemplate.update(
                """
                UPDATE billing_order
                SET status = 'PAID', paid_amount_minor = payable_amount_minor,
                    paid_at = COALESCE(paid_at, ?), updated_at = ?
                WHERE id = ? AND status IN ('CREATED', 'PENDING_PAYMENT', 'CLOSED', 'PAID')
                """,
                now,
                now,
                payment.get("order_id")
        );
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
            jdbcTemplate.update(
                    """
                    UPDATE payment_webhook_event
                    SET process_status = 'PROCESSED', processed_at = ?
                    WHERE provider = ? AND provider_event_id = ?
                    """,
                    LocalDateTime.now(),
                    normalizedProvider,
                    eventId
            );
            return Map.of("status", "accepted", "duplicate", false);
        } catch (RuntimeException exception) {
            jdbcTemplate.update(
                    """
                    UPDATE payment_webhook_event
                    SET process_status = 'FAILED', processed_at = ?, error_code = ?
                    WHERE provider = ? AND provider_event_id = ?
                    """,
                    LocalDateTime.now(),
                    "VALIDATION_FAILED",
                    normalizedProvider,
                    eventId
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
            jdbcTemplate.update(
                    """
                    UPDATE payment_webhook_event
                    SET process_status = 'PROCESSED', processed_at = ?
                    WHERE provider = 'ALIPAY' AND provider_event_id = ?
                    """,
                    LocalDateTime.now(),
                    eventId
            );
            return true;
        } catch (RuntimeException exception) {
            jdbcTemplate.update(
                    """
                    UPDATE payment_webhook_event
                    SET process_status = 'FAILED', processed_at = ?, error_code = 'VALIDATION_FAILED'
                    WHERE provider = 'ALIPAY' AND provider_event_id = ?
                    """,
                    LocalDateTime.now(),
                    eventId
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
            jdbcTemplate.update(
                    """
                    UPDATE payment_webhook_event
                    SET process_status = 'PROCESSED', processed_at = ?
                    WHERE provider = 'WECHAT_PAY' AND provider_event_id = ?
                    """,
                    LocalDateTime.now(),
                    notification.eventId()
            );
            return true;
        } catch (RuntimeException exception) {
            jdbcTemplate.update(
                    """
                    UPDATE payment_webhook_event
                    SET process_status = 'FAILED', processed_at = ?, error_code = 'VALIDATION_FAILED'
                    WHERE provider = 'WECHAT_PAY' AND provider_event_id = ?
                    """,
                    LocalDateTime.now(),
                    notification.eventId()
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
        return jdbcTemplate.query(
                """
                SELECT order_no
                FROM billing_order
                WHERE status IN ('CREATED', 'PENDING_PAYMENT')
                  AND expires_at <= CURRENT_TIMESTAMP(6)
                ORDER BY expires_at
                LIMIT ?
                """,
                (resultSet, rowNum) -> resultSet.getString(1),
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
        List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                "SELECT * FROM commercial_account WHERE user_id = ?",
                userId
        );
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                """
                INSERT IGNORE INTO commercial_account (
                    id, user_id, status, version, created_at, updated_at
                ) VALUES (?, ?, 'ACTIVE', 0, ?, ?)
                """,
                nextId(),
                userId,
                now,
                now
        );
        return jdbcTemplate.queryForMap(
                "SELECT * FROM commercial_account WHERE user_id = ? FOR UPDATE",
                userId
        );
    }

    private Map<String, Object> currentPlan(long userId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT o.order_no, o.product_snapshot, o.fulfilled_at,
                       MAX(g.valid_from) AS valid_from, MAX(g.expires_at) AS expires_at
                FROM billing_order o
                JOIN entitlement_grant g ON g.source_type = 'ORDER' AND g.source_id = o.order_no
                WHERE o.user_id = ? AND o.status IN ('FULFILLED', 'REFUNDING')
                  AND g.status = 'ACTIVE'
                  AND JSON_UNQUOTE(JSON_EXTRACT(o.product_snapshot, '$.productType')) = 'PLAN'
                  AND (g.expires_at IS NULL OR g.expires_at > CURRENT_TIMESTAMP(6))
                GROUP BY o.id, o.order_no, o.product_snapshot, o.fulfilled_at
                ORDER BY expires_at DESC, o.fulfilled_at DESC
                LIMIT 1
                """,
                userId
        );
        if (rows.isEmpty()) {
            return Map.of(
                    "code", "FREE",
                    "name", "免费版",
                    "status", "ACTIVE",
                    "benefits", List.of("基础聊天、记忆与免费 AI 额度")
            );
        }
        Map<String, Object> row = rows.get(0);
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
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT v.*, p.product_code, p.product_type, p.display_name, p.description
                FROM billing_price_version v
                JOIN billing_product p ON p.id = v.product_id
                WHERE v.version_code = ?
                  AND v.status = 'PUBLISHED'
                  AND p.status = 'ACTIVE'
                  AND (v.sell_from IS NULL OR v.sell_from <= CURRENT_TIMESTAMP(6))
                  AND (v.sell_until IS NULL OR v.sell_until > CURRENT_TIMESTAMP(6))
                """,
                normalizeCode(versionCode, 64)
        );
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "价格已下架或暂不可售。");
        }
        return rows.get(0);
    }

    private Map<String, Object> scopedOrder(long userId, String orderNo, boolean forUpdate) {
        List<Map<String, Object>> rows = jdbcTemplate.query(
                orderSelect() + """
                WHERE o.order_no = ? AND o.user_id = ?
                """ + (forUpdate ? " FOR UPDATE" : ""),
                (resultSet, rowNum) -> orderDatabaseRow(resultSet),
                orderNo,
                userId
        );
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return rows.get(0);
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

    private Map<String, Object> requirePayment(String paymentNo, boolean forUpdate) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT p.*, o.order_no, o.status AS order_status, o.user_id, o.account_id
                FROM payment_attempt p
                JOIN billing_order o ON o.id = p.order_id
                WHERE p.payment_no = ?
                """ + (forUpdate ? " FOR UPDATE" : ""),
                safe(paymentNo, 64)
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
        jdbcTemplate.update(
                """
                UPDATE payment_attempt
                SET status = 'CLOSED', closed_at = ?, updated_at = ?
                WHERE id = ? AND status IN ('INITIATED', 'PENDING')
                """,
                now,
                now,
                payment.get("id")
        );
        jdbcTemplate.update(
                """
                UPDATE billing_order
                SET status = 'CLOSED', closed_at = ?, updated_at = ?
                WHERE id = ? AND status IN ('CREATED', 'PENDING_PAYMENT')
                """,
                now,
                now,
                payment.get("order_id")
        );
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

    private Map<String, Object> orderDatabaseRow(ResultSet resultSet) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", resultSet.getLong("id"));
        row.put("order_no", resultSet.getString("order_no"));
        row.put("user_id", resultSet.getLong("user_id"));
        row.put("account_id", resultSet.getLong("account_id"));
        row.put("order_type", resultSet.getString("order_type"));
        row.put("status", resultSet.getString("status"));
        row.put("product_code", resultSet.getString("product_code"));
        row.put("price_version_code", resultSet.getString("price_version_code"));
        row.put("product_snapshot", resultSet.getString("product_snapshot"));
        row.put("currency", resultSet.getString("currency"));
        row.put("original_amount_minor", resultSet.getLong("original_amount_minor"));
        row.put("discount_amount_minor", resultSet.getLong("discount_amount_minor"));
        row.put("payable_amount_minor", resultSet.getLong("payable_amount_minor"));
        row.put("paid_amount_minor", resultSet.getLong("paid_amount_minor"));
        row.put("client_request_id", resultSet.getString("client_request_id"));
        row.put("expires_at", localDateTime(resultSet.getObject("expires_at")));
        row.put("paid_at", localDateTime(resultSet.getObject("paid_at")));
        row.put("fulfilled_at", localDateTime(resultSet.getObject("fulfilled_at")));
        row.put("closed_at", localDateTime(resultSet.getObject("closed_at")));
        row.put("created_at", localDateTime(resultSet.getObject("created_at")));
        row.put("updated_at", localDateTime(resultSet.getObject("updated_at")));
        try {
            row.put("payment_no", resultSet.getString("payment_no"));
            row.put("payment_status", resultSet.getString("payment_status"));
            row.put("payment_provider", resultSet.getString("payment_provider"));
            row.put("refund_status", resultSet.getString("refund_status"));
            row.put("refund_succeeded_at", localDateTime(resultSet.getObject("refund_succeeded_at")));
        } catch (SQLException ignored) {
            // Internal locking queries intentionally may select only the order table.
        }
        return row;
    }

    private String orderSelect() {
        return """
                SELECT o.*,
                       (SELECT p.payment_no FROM payment_attempt p
                        WHERE p.order_id = o.id ORDER BY p.created_at DESC, p.id DESC LIMIT 1) AS payment_no,
                       (SELECT p.status FROM payment_attempt p
                        WHERE p.order_id = o.id ORDER BY p.created_at DESC, p.id DESC LIMIT 1) AS payment_status,
                       (SELECT p.provider FROM payment_attempt p
                        WHERE p.order_id = o.id ORDER BY p.created_at DESC, p.id DESC LIMIT 1) AS payment_provider,
                       (SELECT r.status FROM refund_order r
                        WHERE r.order_id = o.id ORDER BY r.created_at DESC, r.id DESC LIMIT 1) AS refund_status,
                       (SELECT r.succeeded_at FROM refund_order r
                        WHERE r.order_id = o.id ORDER BY r.created_at DESC, r.id DESC LIMIT 1) AS refund_succeeded_at
                FROM billing_order o
                """;
    }

    private void enqueue(
            String aggregateType,
            String aggregateId,
            String eventType,
            int version,
            Map<String, Object> payload
    ) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                """
                INSERT IGNORE INTO billing_outbox (
                    id, aggregate_type, aggregate_id, event_type, event_version,
                    payload_json, status, retry_count, next_attempt_at, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?)
                """,
                nextId(),
                normalize(aggregateType),
                safe(aggregateId, 128),
                normalize(eventType),
                version,
                json(payload),
                now,
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
        return jdbcTemplate.update(
                """
                INSERT IGNORE INTO payment_webhook_event (
                    id, provider, provider_event_id, payload_hash, signature_valid,
                    process_status, payment_no, received_at, error_code
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
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
        jdbcTemplate.update(
                """
                UPDATE billing_order
                SET status = 'CLOSED', closed_at = ?, updated_at = ?
                WHERE id = ? AND status IN ('CREATED', 'PENDING_PAYMENT')
                """,
                now,
                now,
                orderId
        );
        jdbcTemplate.update(
                """
                UPDATE payment_attempt
                SET status = 'CLOSED', closed_at = ?, updated_at = ?
                WHERE order_id = ? AND status IN ('INITIATED', 'PENDING')
                """,
                now,
                now,
                orderId
        );
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
