package com.example.aiapp.billing;

import com.example.aiapp.billing.payment.PaymentGateway;
import com.example.aiapp.billing.payment.WechatPayPaymentGateway;
import com.example.aiapp.usage.UsageEventRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
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
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class BillingService {

    private static final DateTimeFormatter BUSINESS_NO_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final AtomicLong ID_SEQUENCE =
            new AtomicLong(System.currentTimeMillis() * 1_000L);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final BillingProperties properties;
    private final PaymentGateway paymentGateway;
    private final BillingMetrics metrics;

    public BillingService(
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

    public List<Map<String, Object>> catalog() {
        require(properties.catalogEnabled());
        return jdbcTemplate.query(
                """
                SELECT p.product_code, p.product_type, p.display_name, p.description,
                       v.version_code, v.currency, v.amount_minor, v.included_credit_micros,
                       v.validity_days, v.entitlement_snapshot, v.sell_until
                FROM billing_product p
                JOIN billing_price_version v ON v.product_id = p.id
                WHERE p.status = 'ACTIVE'
                  AND v.status = 'PUBLISHED'
                  AND (v.sell_from IS NULL OR v.sell_from <= CURRENT_TIMESTAMP(6))
                  AND (v.sell_until IS NULL OR v.sell_until > CURRENT_TIMESTAMP(6))
                ORDER BY CASE p.product_type
                           WHEN 'PLAN' THEN 1
                           WHEN 'CREDIT_PACK' THEN 2
                           ELSE 3
                         END,
                         v.amount_minor
                """,
                (resultSet, rowNum) -> catalogRow(resultSet)
        );
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> account(long userId) {
        require(properties.userViewEnabled() && properties.ledgerEnabled());
        Map<String, Object> account = ensureAccount(userId);
        long accountId = number(account.get("id"));
        grantWelcomeCredits(accountId, userId);
        long available = availableCredits(accountId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", account.get("status"));
        response.put("plan", currentPlan(userId));
        response.put("availableCreditMicros", available);
        response.put("totalGrantedCreditMicros", aggregateLong(
                "SELECT COALESCE(SUM(quantity_micros), 0) FROM entitlement_grant WHERE account_id = ?",
                accountId
        ));
        response.put("totalConsumedCreditMicros", aggregateLong(
                """
                SELECT COALESCE(SUM(credit_micros), 0)
                FROM commercial_ledger_entry
                WHERE account_id = ? AND entry_type = 'USAGE_DEBIT'
                """,
                accountId
        ));
        response.put("nextExpiryAt", jdbcTemplate.query(
                """
                SELECT MIN(expires_at)
                FROM entitlement_grant
                WHERE account_id = ? AND status = 'ACTIVE' AND remaining_micros > 0
                  AND expires_at IS NOT NULL AND expires_at > CURRENT_TIMESTAMP(6)
                """,
                resultSet -> resultSet.next() ? localDateTime(resultSet.getObject(1)) : null,
                accountId
        ));
        response.put("commercialQuotaEnforced", properties.quotaEnforcementEnabled());
        response.put("settlementMode", properties.settlementShadowEnabled() ? "SHADOW" : "ACTIVE");
        response.put("checkoutEnabled", properties.checkoutEnabled());
        return response;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<Map<String, Object>> ledger(long userId, String type, int limit) {
        require(properties.userViewEnabled() && properties.ledgerEnabled());
        long accountId = number(ensureAccount(userId).get("id"));
        grantWelcomeCredits(accountId, userId);
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        String normalizedType = normalizeNullable(type);
        String sql = """
                SELECT e.id, e.entry_type, e.direction, e.credit_micros,
                       e.balance_after_micros, e.source_type, e.source_id,
                       e.occurred_at, e.expires_at, e.metadata_json
                FROM commercial_ledger_entry e
                WHERE e.account_id = ?
                """ + (normalizedType == null ? "" : " AND e.entry_type = ?") + """
                ORDER BY e.occurred_at DESC, e.id DESC
                LIMIT ?
                """;
        Object[] arguments = normalizedType == null
                ? new Object[]{accountId, boundedLimit}
                : new Object[]{accountId, normalizedType, boundedLimit};
        return jdbcTemplate.query(sql, (resultSet, rowNum) -> ledgerRow(resultSet), arguments);
    }

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
            return order(userId, String.valueOf(existing.get(0).get("order_no")));
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
                return orderResponse(scopedOrder(
                        userId,
                        String.valueOf(raced.get(0).get("order_no")),
                        true
                ));
            }
            throw exception;
        }
        metrics.orderCreated();
        return order(userId, orderNo);
    }

    public List<Map<String, Object>> orders(long userId, int limit) {
        require(properties.userViewEnabled());
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        return jdbcTemplate.query(
                orderSelect() + """
                WHERE o.user_id = ?
                ORDER BY o.created_at DESC
                LIMIT ?
                """,
                (resultSet, rowNum) -> orderRow(resultSet),
                userId,
                boundedLimit
        );
    }

    public Map<String, Object> order(long userId, String orderNo) {
        require(properties.userViewEnabled());
        return orderResponse(scopedOrder(userId, safe(orderNo, 64), false));
    }

    @Transactional
    public Map<String, Object> refreshOrderStatus(long userId, String orderNo) {
        require(properties.userViewEnabled());
        Map<String, Object> order = scopedOrder(userId, safe(orderNo, 64), true);
        if (!List.of("CREATED", "PENDING_PAYMENT").contains(normalize(String.valueOf(order.get("status"))))) {
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
            confirmPayment(
                    String.valueOf(payment.get("payment_no")),
                    result.providerTransactionId(),
                    number(payment.get("amount_minor")),
                    String.valueOf(payment.get("currency")),
                    String.valueOf(payment.get("provider"))
            );
        } else if ("CLOSED".equals(providerStatus)) {
            markPaymentAndOrderClosed(payment);
        }
        return order(userId, orderNo);
    }

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
        if (existing != null && "PENDING".equals(normalize(String.valueOf(existing.get("status"))))
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

    @Transactional
    public Map<String, Object> closeOrder(long userId, String orderNo) {
        Map<String, Object> order = scopedOrder(userId, safe(orderNo, 64), true);
        String status = normalize(String.valueOf(order.get("status")));
        if (!List.of("CREATED", "PENDING_PAYMENT").contains(status)) {
            return order(userId, orderNo);
        }
        Map<String, Object> payment = currentPayment(number(order.get("id")));
        if (payment != null) {
            PaymentGateway.PaymentResult result =
                    paymentGateway.queryPayment(String.valueOf(payment.get("payment_no")));
            if ("SUCCEEDED".equals(normalize(result.status()))) {
                confirmPayment(
                        String.valueOf(payment.get("payment_no")),
                        result.providerTransactionId(),
                        number(payment.get("amount_minor")),
                        String.valueOf(payment.get("currency")),
                        paymentGateway.provider()
                );
                return order(userId, orderNo);
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
        }
        if (payment == null) {
            closeExpiredOrder(number(order.get("id")));
        }
        return order(userId, orderNo);
    }

    @Transactional
    public Map<String, Object> confirmMockPayment(String paymentNo) {
        if (!"MOCK".equals(paymentGateway.provider())) {
            throw badRequest("当前环境不是沙箱支付。");
        }
        Map<String, Object> payment = requirePayment(paymentNo, true);
        String providerTransactionId = "mock-" + UUID.randomUUID();
        confirmPayment(
                paymentNo,
                providerTransactionId,
                number(payment.get("amount_minor")),
                String.valueOf(payment.get("currency")),
                paymentGateway.provider()
        );
        return paymentStatus(paymentNo);
    }

    @Transactional
    public void confirmPayment(
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
        if (!List.of("CREATED", "PENDING_PAYMENT", "CLOSED", "PAID", "FULFILLING").contains(orderStatus)) {
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

    public Map<String, Object> paymentStatus(String paymentNo) {
        Map<String, Object> payment = requirePayment(paymentNo, false);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("paymentNo", payment.get("payment_no"));
        response.put("orderNo", payment.get("order_no"));
        response.put("paymentStatus", payment.get("status"));
        response.put("orderStatus", payment.get("order_status"));
        return response;
    }

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
            if (properties.merchantId() != null
                    && !properties.merchantId().isBlank()
                    && !properties.merchantId().equals(String.valueOf(payload.get("merchantId")))) {
                throw badRequest("商户号不匹配。");
            }
            confirmPayment(
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
                confirmPayment(paymentNo, tradeNo, amountMinor, "CNY", "ALIPAY");
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
                confirmPayment(
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

    public List<String> pendingFulfillmentOrders(int limit) {
        if (!properties.fulfillmentWorkerEnabled()) {
            return List.of();
        }
        return jdbcTemplate.query(
                """
                SELECT aggregate_id
                FROM billing_outbox
                WHERE event_type = 'FULFILL_ORDER'
                  AND status IN ('PENDING', 'RETRY')
                  AND next_attempt_at <= CURRENT_TIMESTAMP(6)
                ORDER BY created_at
                LIMIT ?
                """,
                (resultSet, rowNum) -> resultSet.getString(1),
                Math.max(1, Math.min(limit, 50))
        );
    }

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

    @Transactional
    public void closeExpiredPendingOrder(String orderNo) {
        Map<String, Object> order = requireOrder(orderNo, true);
        if (!List.of("CREATED", "PENDING_PAYMENT").contains(normalize(String.valueOf(order.get("status"))))) {
            return;
        }
        Map<String, Object> payment = currentPayment(number(order.get("id")));
        if (payment != null) {
            PaymentGateway.PaymentResult result =
                    paymentGateway.queryPayment(String.valueOf(payment.get("payment_no")));
            if ("SUCCEEDED".equals(normalize(result.status()))) {
                confirmPayment(
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
        }
        if (payment == null) {
            closeExpiredOrder(number(order.get("id")));
        }
    }

    public List<Long> expiredGrantIds(int limit) {
        if (!properties.ledgerEnabled()) {
            return List.of();
        }
        return jdbcTemplate.query(
                """
                SELECT id
                FROM entitlement_grant
                WHERE status = 'ACTIVE' AND remaining_micros > 0
                  AND expires_at IS NOT NULL AND expires_at <= CURRENT_TIMESTAMP(6)
                ORDER BY expires_at
                LIMIT ?
                """,
                (resultSet, rowNum) -> resultSet.getLong(1),
                Math.max(1, Math.min(limit, 200))
        );
    }

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

    @Transactional
    public void expireGrant(long grantId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM entitlement_grant WHERE id = ? FOR UPDATE",
                grantId
        );
        if (rows.isEmpty()) {
            return;
        }
        Map<String, Object> grant = rows.get(0);
        long remaining = number(grant.get("remaining_micros"));
        if (!"ACTIVE".equals(normalize(String.valueOf(grant.get("status"))))
                || remaining <= 0
                || localDateTime(grant.get("expires_at")) == null
                || localDateTime(grant.get("expires_at")).isAfter(LocalDateTime.now())) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                """
                UPDATE entitlement_grant
                SET status = 'EXPIRED', remaining_micros = 0, updated_at = ?
                WHERE id = ?
                """,
                now,
                grantId
        );
        long accountId = number(grant.get("account_id"));
        jdbcTemplate.update(
                """
                INSERT IGNORE INTO commercial_ledger_entry (
                    id, account_id, grant_id, entry_type, direction,
                    credit_micros, balance_after_micros, source_type, source_id,
                    idempotency_key, occurred_at, expires_at, metadata_json, created_at
                ) VALUES (?, ?, ?, 'EXPIRATION', 'DEBIT', ?, ?, 'GRANT', ?, ?, ?, ?, ?, ?)
                """,
                nextId(),
                accountId,
                grantId,
                remaining,
                availableCredits(accountId),
                String.valueOf(grantId),
                safe("EXPIRATION:" + grantId, 128),
                now,
                grant.get("expires_at"),
                json(Map.of()),
                now
        );
    }

    @Transactional
    public void fulfillOrder(String orderNo) {
        Map<String, Object> order = requireOrder(orderNo, true);
        String status = normalize(String.valueOf(order.get("status")));
        if ("FULFILLED".equals(status)) {
            markOutboxProcessed(orderNo, "FULFILL_ORDER");
            return;
        }
        if (!List.of("PAID", "FULFILLING").contains(status)) {
            throw conflict("订单尚未完成支付确认。");
        }
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                "UPDATE billing_order SET status = 'FULFILLING', updated_at = ? WHERE id = ?",
                now,
                order.get("id")
        );
        Map<String, Object> snapshot = jsonMap(order.get("product_snapshot"));
        long credits = number(snapshot.get("includedCreditMicros"));
        String productType = normalize(String.valueOf(snapshot.get("productType")));
        LocalDateTime validFrom = now;
        if ("RENEWAL".equals(normalize(String.valueOf(order.get("order_type"))))
                && "PLAN".equals(productType)) {
            LocalDateTime currentExpiry = latestPlanExpiry(number(order.get("account_id")));
            if (currentExpiry != null && currentExpiry.isAfter(now)) {
                validFrom = currentExpiry;
            }
        }
        Integer validityDays = nullableInt(snapshot.get("validityDays"));
        LocalDateTime expiresAt = validityDays == null ? null : validFrom.plusDays(validityDays);
        grantCredits(
                number(order.get("account_id")),
                "PURCHASE",
                "ORDER",
                orderNo,
                "AI_CREDIT",
                String.valueOf(order.get("price_version_code")),
                credits,
                validFrom,
                expiresAt,
                "PURCHASE_GRANT",
                Map.of(
                        "productCode", String.valueOf(order.get("product_code")),
                        "productType", productType
                )
        );
        jdbcTemplate.update(
                """
                UPDATE billing_order
                SET status = 'FULFILLED', fulfilled_at = COALESCE(fulfilled_at, ?), updated_at = ?
                WHERE id = ?
                """,
                now,
                now,
                order.get("id")
        );
        markOutboxProcessed(orderNo, "FULFILL_ORDER");
        LocalDateTime paidAt = localDateTime(order.get("paid_at"));
        metrics.orderFulfilled(paidAt == null ? null : Duration.between(paidAt, now));
    }

    public void markFulfillmentFailed(String orderNo, Exception exception) {
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        jdbcTemplate.update(
                """
                UPDATE billing_outbox
                SET status = CASE WHEN retry_count >= 9 THEN 'DEAD' ELSE 'RETRY' END,
                    retry_count = retry_count + 1,
                    next_attempt_at = DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL LEAST(3600, POW(2, retry_count + 1)) SECOND),
                    last_error = ?
                WHERE aggregate_type = 'ORDER' AND aggregate_id = ? AND event_type = 'FULFILL_ORDER'
                  AND status <> 'PROCESSED'
                """,
                safe(message, 512),
                orderNo
        );
    }

    @Transactional
    public String reserveCommercial(long userId, String turnId) {
        if (!properties.ledgerEnabled()
                || properties.settlementShadowEnabled()
                || !properties.quotaEnforcementEnabled()) {
            return null;
        }
        Map<String, Object> account = ensureAccount(userId);
        long accountId = number(account.get("id"));
        grantWelcomeCredits(accountId, userId);
        jdbcTemplate.queryForMap(
                "SELECT id FROM commercial_account WHERE id = ? FOR UPDATE",
                accountId
        );
        long available = availableCredits(accountId);
        long active = aggregateLong(
                """
                SELECT COALESCE(SUM(reserved_credit_micros - consumed_credit_micros), 0)
                FROM commercial_reservation
                WHERE account_id = ? AND status = 'ACTIVE' AND expires_at > CURRENT_TIMESTAMP(6)
                """,
                accountId
        );
        long reserve = Math.max(1, properties.reservationCreditMicros());
        if (available - active < reserve) {
            metrics.commercialInsufficient();
            throw new ResponseStatusException(
                    HttpStatus.PAYMENT_REQUIRED,
                    "当前 AI 额度不足，请升级套餐或购买额度后主动重试。"
            );
        }
        String reservationId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                """
                INSERT INTO commercial_reservation (
                    id, account_id, user_id, turn_id, reserved_credit_micros,
                    consumed_credit_micros, status, expires_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 0, 'ACTIVE', ?, ?, ?)
                """,
                reservationId,
                accountId,
                userId,
                safeNullable(turnId, 128),
                reserve,
                now.plusMinutes(Math.max(1, properties.reservationTtlMinutes())),
                now,
                now
        );
        return reservationId;
    }

    public void releaseCommercialReservation(String reservationId) {
        if (!StringUtils.hasText(reservationId)) {
            return;
        }
        jdbcTemplate.update(
                """
                UPDATE commercial_reservation
                SET status = 'RELEASED', updated_at = ?
                WHERE id = ? AND status = 'ACTIVE'
                """,
                LocalDateTime.now(),
                reservationId
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void settleUsage(UsageEventRequest event) {
        if (!properties.ledgerEnabled()
                || event == null
                || !"SUCCEEDED".equals(normalize(event.status()))) {
            return;
        }
        Map<String, Object> pricing = activeUsagePricing();
        if (pricing == null) {
            return;
        }
        long charge = calculateCharge(event, jsonMap(pricing.get("rules_json")));
        if (charge <= 0) {
            return;
        }
        long userId = Long.parseLong(event.userId());
        Map<String, Object> account = ensureAccount(userId);
        long accountId = number(account.get("id"));
        grantWelcomeCredits(accountId, userId);
        String reservationId = activeReservationId(userId, event.turnId());
        String initialStatus = properties.settlementShadowEnabled() ? "SHADOW" : "PENDING";
        int inserted = jdbcTemplate.update(
                """
                INSERT IGNORE INTO billable_usage (
                    id, usage_event_id, user_id, account_id, pricing_version_code,
                    charge_credit_micros, usage_source, status, reservation_id,
                    metadata_json, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                nextId(),
                event.eventId(),
                userId,
                accountId,
                pricing.get("version_code"),
                charge,
                normalize(event.usageSource()),
                initialStatus,
                reservationId,
                json(Map.of(
                        "callType", normalize(event.callType()),
                        "taskType", normalize(event.taskType()),
                        "estimated", !"PROVIDER_REPORTED".equals(normalize(event.usageSource()))
                )),
                LocalDateTime.now()
        );
        if (inserted == 0) {
            metrics.duplicatePrevented();
            return;
        }
        if (properties.settlementShadowEnabled()) {
            jdbcTemplate.update(
                    "UPDATE billable_usage SET settled_at = ? WHERE usage_event_id = ?",
                    LocalDateTime.now(),
                    event.eventId()
            );
            metrics.commercialInsufficient();
            return;
        }
        Long firstLedgerId = debitCredits(accountId, event.eventId(), charge, event);
        if (firstLedgerId == null) {
            jdbcTemplate.update(
                    """
                    UPDATE billable_usage
                    SET status = 'INSUFFICIENT', settled_at = ?
                    WHERE usage_event_id = ?
                    """,
                    LocalDateTime.now(),
                    event.eventId()
            );
            return;
        }
        jdbcTemplate.update(
                """
                UPDATE billable_usage
                SET status = 'SETTLED', ledger_entry_id = ?, settled_at = ?
                WHERE usage_event_id = ?
                """,
                firstLedgerId,
                LocalDateTime.now(),
                event.eventId()
        );
        if (reservationId != null) {
            jdbcTemplate.update(
                    """
                    UPDATE commercial_reservation
                    SET consumed_credit_micros = LEAST(reserved_credit_micros, consumed_credit_micros + ?),
                        status = 'SETTLED', updated_at = ?
                    WHERE id = ?
                    """,
                    charge,
                    LocalDateTime.now(),
                    reservationId
            );
        }
        metrics.commercialDebit(charge);
    }

    public List<String> pendingUsageEventIds(int limit) {
        if (!properties.ledgerEnabled() || activeUsagePricing() == null) {
            return List.of();
        }
        return jdbcTemplate.query(
                """
                SELECT e.event_id
                FROM ai_usage_event e
                LEFT JOIN billable_usage b ON b.usage_event_id = e.event_id
                WHERE b.usage_event_id IS NULL AND e.status = 'SUCCEEDED'
                ORDER BY e.received_at
                LIMIT ?
                """,
                (resultSet, rowNum) -> resultSet.getString(1),
                Math.max(1, Math.min(limit, 200))
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void settleUsageEvent(String eventId) {
        List<UsageEventRequest> events = jdbcTemplate.query(
                "SELECT * FROM ai_usage_event WHERE event_id = ?",
                (resultSet, rowNum) -> new UsageEventRequest(
                        resultSet.getString("event_id"),
                        String.valueOf(resultSet.getLong("user_id")),
                        resultSet.getString("conversation_id"),
                        resultSet.getString("turn_id"),
                        resultSet.getString("trace_id"),
                        resultSet.getString("reservation_id"),
                        resultSet.getString("call_type"),
                        resultSet.getString("task_type"),
                        resultSet.getString("provider"),
                        resultSet.getString("model"),
                        resultSet.getString("route_version"),
                        resultSet.getLong("input_tokens"),
                        resultSet.getLong("output_tokens"),
                        resultSet.getLong("cache_read_tokens"),
                        resultSet.getLong("cache_write_tokens"),
                        resultSet.getString("usage_source"),
                        resultSet.getLong("estimated_cost_micros"),
                        resultSet.getString("pricing_version"),
                        resultSet.getString("currency"),
                        resultSet.getLong("latency_millis"),
                        resultSet.getString("status"),
                        resultSet.getString("error_category"),
                        resultSet.getTimestamp("occurred_at").toLocalDateTime().atOffset(ZoneOffset.ofHours(8))
                ),
                safe(eventId, 64)
        );
        if (!events.isEmpty()) {
            settleUsage(events.get(0));
        }
    }

    @Transactional
    public Map<String, Object> createProduct(BillingRequests.CreateProduct request) {
        List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                "SELECT * FROM billing_product WHERE product_code = ?",
                normalizeCode(request.productCode(), 64)
        );
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        long id = nextId();
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                """
                INSERT INTO billing_product (
                    id, product_code, product_type, display_name, description,
                    status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                """,
                id,
                normalizeCode(request.productCode(), 64),
                allowedProductType(request.productType()),
                safe(request.displayName(), 128),
                safeNullable(request.description(), 512),
                now,
                now
        );
        return jdbcTemplate.queryForMap("SELECT * FROM billing_product WHERE id = ?", id);
    }

    @Transactional
    public Map<String, Object> createPrice(BillingRequests.CreatePrice request) {
        List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                "SELECT * FROM billing_price_version WHERE version_code = ?",
                normalizeCode(request.versionCode(), 64)
        );
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        Map<String, Object> product = requireProduct(request.productCode());
        if (request.validityDays() != null && request.validityDays() <= 0) {
            throw badRequest("有效期天数必须大于 0。");
        }
        if (request.sellFrom() != null
                && request.sellUntil() != null
                && !request.sellUntil().isAfter(request.sellFrom())) {
            throw badRequest("停售时间必须晚于开售时间。");
        }
        long id = nextId();
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> entitlements = new LinkedHashMap<>();
        entitlements.put("benefits", request.benefits() == null ? List.of() : request.benefits());
        entitlements.put("includedCreditMicros", request.includedCreditMicros());
        jdbcTemplate.update(
                """
                INSERT INTO billing_price_version (
                    id, product_id, version_code, currency, amount_minor,
                    included_credit_micros, validity_days, entitlement_snapshot,
                    pricing_rule_snapshot, status, sell_from, sell_until, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?)
                """,
                id,
                product.get("id"),
                normalizeCode(request.versionCode(), 64),
                normalizeCurrency(request.currency()),
                request.amountMinor(),
                request.includedCreditMicros(),
                request.validityDays(),
                json(entitlements),
                json(request.pricingRules() == null ? Map.of() : request.pricingRules()),
                request.sellFrom(),
                request.sellUntil(),
                now
        );
        return jdbcTemplate.queryForMap("SELECT * FROM billing_price_version WHERE id = ?", id);
    }

    @Transactional
    public Map<String, Object> publishPrice(String versionCode) {
        String code = normalizeCode(versionCode, 64);
        Map<String, Object> price = requirePrice(code);
        if ("PUBLISHED".equals(normalize(String.valueOf(price.get("status"))))) {
            return price;
        }
        if (!"DRAFT".equals(normalize(String.valueOf(price.get("status"))))) {
            throw conflict("只有草稿价格可以发布。");
        }
        jdbcTemplate.update(
                """
                UPDATE billing_price_version
                SET status = 'PUBLISHED', published_at = ?
                WHERE id = ? AND status = 'DRAFT'
                """,
                LocalDateTime.now(),
                price.get("id")
        );
        return requirePrice(code);
    }

    @Transactional
    public Map<String, Object> publishUsagePricing(BillingRequests.PublishUsagePricing request) {
        List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                "SELECT * FROM billing_usage_pricing_version WHERE version_code = ?",
                normalizeCode(request.versionCode(), 64)
        );
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        LocalDateTime effectiveFrom = request.effectiveFrom() == null
                ? LocalDateTime.now()
                : request.effectiveFrom();
        if (request.effectiveUntil() != null && !request.effectiveUntil().isAfter(effectiveFrom)) {
            throw badRequest("计价结束时间必须晚于生效时间。");
        }
        validatePricingRules(request.rules());
        long id = nextId();
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                """
                INSERT INTO billing_usage_pricing_version (
                    id, version_code, rules_json, status, effective_from,
                    effective_until, created_at, published_at
                ) VALUES (?, ?, ?, 'PUBLISHED', ?, ?, ?, ?)
                """,
                id,
                normalizeCode(request.versionCode(), 64),
                json(request.rules()),
                effectiveFrom,
                request.effectiveUntil(),
                now,
                now
        );
        return jdbcTemplate.queryForMap(
                "SELECT * FROM billing_usage_pricing_version WHERE id = ?",
                id
        );
    }

    @Transactional
    public Map<String, Object> grantManualCredits(
            long userId,
            BillingRequests.GrantCredits request
    ) {
        Map<String, Object> account = ensureAccount(userId);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = request.validityDays() == null
                ? null
                : now.plusDays(Math.max(1, request.validityDays()));
        grantCredits(
                number(account.get("id")),
                "MANUAL",
                "OPERATOR",
                safe(request.requestId(), 128),
                "AI_CREDIT",
                null,
                request.creditMicros(),
                now,
                expiresAt,
                "MANUAL_ADJUSTMENT",
                Map.of("reason", request.reason() == null ? "" : request.reason())
        );
        return account(userId);
    }

    public List<Map<String, Object>> refunds(long userId) {
        require(properties.userViewEnabled());
        return jdbcTemplate.query(
                """
                SELECT r.refund_no, r.status, r.currency, r.amount_minor,
                       r.reason_code, r.reason_note, r.succeeded_at, r.created_at,
                       o.order_no, o.product_snapshot
                FROM refund_order r
                JOIN billing_order o ON o.id = r.order_id
                WHERE r.user_id = ?
                ORDER BY r.created_at DESC
                """,
                (resultSet, rowNum) -> refundRow(resultSet),
                userId
        );
    }

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

    public List<Map<String, Object>> reconciliation(String status, int limit) {
        require(properties.reconciliationEnabled());
        String normalizedStatus = normalizeNullable(status);
        int boundedLimit = Math.max(1, Math.min(limit, 200));
        String sql = """
                SELECT *
                FROM payment_reconciliation_item
                WHERE 1 = 1
                """ + (normalizedStatus == null ? "" : " AND process_status = ?") + """
                ORDER BY reconciliation_date DESC, created_at DESC
                LIMIT ?
                """;
        Object[] arguments = normalizedStatus == null
                ? new Object[]{boundedLimit}
                : new Object[]{normalizedStatus, boundedLimit};
        return jdbcTemplate.queryForList(sql, arguments);
    }

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
        List<Map<String, Object>> internal = jdbcTemplate.queryForList(
                """
                SELECT payment_no, provider_transaction_id, amount_minor, status
                FROM payment_attempt
                WHERE provider = ? AND created_at >= ? AND created_at < ?
                """,
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

    @Transactional
    public Map<String, Object> resolveReconciliation(
            long id,
            long operatorUserId,
            BillingRequests.ResolveReconciliation request
    ) {
        int updated = jdbcTemplate.update(
                """
                UPDATE payment_reconciliation_item
                SET process_status = 'RESOLVED', assignee_user_id = ?, note = ?,
                    resolved_at = ?, updated_at = ?
                WHERE id = ?
                """,
                operatorUserId,
                safe(request.note(), 512),
                LocalDateTime.now(),
                LocalDateTime.now(),
                id
        );
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return jdbcTemplate.queryForMap(
                "SELECT * FROM payment_reconciliation_item WHERE id = ?",
                id
        );
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
                """
                SELECT *
                FROM commercial_account
                WHERE user_id = ?
                FOR UPDATE
                """,
                userId
        );
    }

    private void grantWelcomeCredits(long accountId, long userId) {
        if (properties.freeCreditMicros() <= 0) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        grantCredits(
                accountId,
                "FREE",
                "FREE_CYCLE",
                "V65_WELCOME_" + userId,
                "AI_CREDIT",
                null,
                properties.freeCreditMicros(),
                now,
                null,
                "FREE_GRANT",
                Map.of("label", "免费版 AI 额度")
        );
    }

    private void grantCredits(
            long accountId,
            String grantType,
            String sourceType,
            String sourceId,
            String entitlementCode,
            String priceVersionCode,
            long quantityMicros,
            LocalDateTime validFrom,
            LocalDateTime expiresAt,
            String ledgerType,
            Map<String, Object> metadata
    ) {
        if (quantityMicros <= 0) {
            return;
        }
        long grantId = nextId();
        LocalDateTime now = LocalDateTime.now();
        int inserted = jdbcTemplate.update(
                """
                INSERT IGNORE INTO entitlement_grant (
                    id, account_id, grant_type, source_type, source_id,
                    entitlement_code, price_version_code, quantity_micros,
                    remaining_micros, valid_from, expires_at, status,
                    metadata_json, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
                """,
                grantId,
                accountId,
                normalize(grantType),
                normalize(sourceType),
                safe(sourceId, 128),
                normalizeCode(entitlementCode, 64),
                safeNullable(priceVersionCode, 64),
                quantityMicros,
                quantityMicros,
                validFrom,
                expiresAt,
                json(metadata),
                now,
                now
        );
        if (inserted == 0) {
            return;
        }
        long balance = availableCredits(accountId);
        jdbcTemplate.update(
                """
                INSERT IGNORE INTO commercial_ledger_entry (
                    id, account_id, grant_id, entry_type, direction,
                    credit_micros, balance_after_micros, source_type, source_id,
                    idempotency_key, occurred_at, expires_at, metadata_json, created_at
                ) VALUES (?, ?, ?, ?, 'CREDIT', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                nextId(),
                accountId,
                grantId,
                normalize(ledgerType),
                quantityMicros,
                balance,
                normalize(sourceType),
                safe(sourceId, 128),
                safe(normalize(ledgerType) + ":" + sourceType + ":" + sourceId + ":" + entitlementCode, 128),
                now,
                expiresAt,
                json(metadata),
                now
        );
    }

    private Long debitCredits(
            long accountId,
            String usageEventId,
            long charge,
            UsageEventRequest event
    ) {
        List<Map<String, Object>> grants = jdbcTemplate.queryForList(
                """
                SELECT id, remaining_micros, expires_at
                FROM entitlement_grant
                WHERE account_id = ? AND status = 'ACTIVE'
                  AND remaining_micros > 0
                  AND valid_from <= CURRENT_TIMESTAMP(6)
                  AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP(6))
                ORDER BY CASE WHEN expires_at IS NULL THEN 1 ELSE 0 END,
                         expires_at, created_at, id
                FOR UPDATE
                """,
                accountId
        );
        long available = grants.stream().mapToLong(row -> number(row.get("remaining_micros"))).sum();
        if (available < charge) {
            return null;
        }
        long remaining = charge;
        Long firstLedgerId = null;
        for (Map<String, Object> grant : grants) {
            if (remaining == 0) break;
            long debit = Math.min(remaining, number(grant.get("remaining_micros")));
            jdbcTemplate.update(
                    """
                    UPDATE entitlement_grant
                    SET remaining_micros = remaining_micros - ?, updated_at = ?
                    WHERE id = ? AND remaining_micros >= ?
                    """,
                    debit,
                    LocalDateTime.now(),
                    grant.get("id"),
                    debit
            );
            long ledgerId = nextId();
            if (firstLedgerId == null) firstLedgerId = ledgerId;
            long balance = availableCredits(accountId);
            jdbcTemplate.update(
                    """
                    INSERT INTO commercial_ledger_entry (
                        id, account_id, grant_id, entry_type, direction,
                        credit_micros, balance_after_micros, source_type, source_id,
                        idempotency_key, occurred_at, expires_at, metadata_json, created_at
                    ) VALUES (?, ?, ?, 'USAGE_DEBIT', 'DEBIT', ?, ?, 'USAGE_EVENT', ?, ?, ?, ?, ?, ?)
                    """,
                    ledgerId,
                    accountId,
                    grant.get("id"),
                    debit,
                    balance,
                    usageEventId,
                    safe("USAGE_DEBIT:" + usageEventId + ":" + grant.get("id"), 128),
                    LocalDateTime.now(),
                    grant.get("expires_at"),
                    json(Map.of(
                            "callType", normalize(event.callType()),
                            "taskType", normalize(event.taskType()),
                            "traceId", event.traceId()
                    )),
                    LocalDateTime.now()
            );
            remaining -= debit;
        }
        return firstLedgerId;
    }

    private void executeRefund(String refundNo, boolean approvedConsumed) {
        Map<String, Object> refund = requireRefund(refundNo, true);
        Map<String, Object> payment = requirePayment(
                String.valueOf(refund.get("payment_no")),
                false
        );
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

    private LocalDateTime latestPlanExpiry(long accountId) {
        return jdbcTemplate.query(
                """
                SELECT MAX(g.expires_at)
                FROM entitlement_grant g
                JOIN billing_order o ON o.order_no = g.source_id AND g.source_type = 'ORDER'
                WHERE g.account_id = ? AND g.status = 'ACTIVE'
                  AND JSON_UNQUOTE(JSON_EXTRACT(o.product_snapshot, '$.productType')) = 'PLAN'
                """,
                resultSet -> resultSet.next() ? localDateTime(resultSet.getObject(1)) : null,
                accountId
        );
    }

    private Map<String, Object> activeUsagePricing() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT version_code, rules_json
                FROM billing_usage_pricing_version
                WHERE status = 'PUBLISHED'
                  AND effective_from <= CURRENT_TIMESTAMP(6)
                  AND (effective_until IS NULL OR effective_until > CURRENT_TIMESTAMP(6))
                ORDER BY effective_from DESC, id DESC
                LIMIT 1
                """
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long calculateCharge(UsageEventRequest event, Map<String, Object> rules) {
        Object rawRule = rules.get(normalize(event.callType()));
        if (!(rawRule instanceof Map<?, ?> rule)) {
            return 0;
        }
        long unit = positiveNumber(rule.get("unit"), 1);
        long creditMicros = positiveNumber(rule.get("creditMicros"), 0);
        long minimum = positiveNumber(rule.get("minimum"), 0);
        long usage = "IMAGE_GENERATION".equals(normalize(event.callType()))
                ? 1
                : event.inputTokens() + event.outputTokens()
                + event.cacheReadTokens() + event.cacheWriteTokens();
        if (usage <= 0 || creditMicros <= 0) {
            return 0;
        }
        long units = BigDecimal.valueOf(usage)
                .divide(BigDecimal.valueOf(unit), 0, RoundingMode.CEILING)
                .longValueExact();
        return Math.max(minimum, Math.multiplyExact(units, creditMicros));
    }

    private void validatePricingRules(Map<String, Object> rules) {
        if (rules == null || rules.isEmpty()) {
            throw badRequest("至少需要一条商业计价规则。");
        }
        for (Map.Entry<String, Object> entry : rules.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> rule)
                    || positiveNumber(rule.get("unit"), 0) <= 0
                    || positiveNumber(rule.get("creditMicros"), 0) <= 0) {
                throw badRequest("计价规则必须包含正数 unit 与 creditMicros。");
            }
        }
    }

    private String activeReservationId(long userId, String turnId) {
        if (!StringUtils.hasText(turnId)) {
            return null;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT id
                FROM commercial_reservation
                WHERE user_id = ? AND turn_id = ?
                  AND status = 'ACTIVE' AND expires_at > CURRENT_TIMESTAMP(6)
                ORDER BY created_at DESC
                LIMIT 1
                """,
                userId,
                turnId
        );
        return rows.isEmpty() ? null : String.valueOf(rows.get(0).get("id"));
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

    private Map<String, Object> requireProduct(String productCode) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM billing_product WHERE product_code = ? AND status = 'ACTIVE'",
                normalizeCode(productCode, 64)
        );
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return rows.get(0);
    }

    private Map<String, Object> requirePrice(String versionCode) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM billing_price_version WHERE version_code = ?",
                normalizeCode(versionCode, 64)
        );
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
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

    private Map<String, Object> orderRow(ResultSet resultSet) throws SQLException {
        return orderResponse(orderDatabaseRow(resultSet));
    }

    private Map<String, Object> orderResponse(Map<String, Object> row) {
        Map<String, Object> snapshot = jsonMap(row.get("product_snapshot"));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("orderNo", row.get("order_no"));
        response.put("status", row.get("status"));
        response.put("orderType", row.get("order_type"));
        response.put("productCode", row.get("product_code"));
        response.put("priceVersionCode", row.get("price_version_code"));
        response.put("product", snapshot);
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
            // The internal locking query intentionally selects only the order table.
        }
        return row;
    }

    private Map<String, Object> catalogRow(ResultSet resultSet) throws SQLException {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("productCode", resultSet.getString("product_code"));
        item.put("productType", resultSet.getString("product_type"));
        item.put("name", resultSet.getString("display_name"));
        item.put("description", resultSet.getString("description"));
        item.put("priceVersionCode", resultSet.getString("version_code"));
        item.put("amount", amount(
                resultSet.getString("currency"),
                resultSet.getLong("amount_minor"),
                0
        ));
        item.put("includedCreditMicros", resultSet.getLong("included_credit_micros"));
        item.put("validityDays", nullableInt(resultSet.getObject("validity_days")));
        item.put("benefits", benefits(resultSet.getString("entitlement_snapshot")));
        item.put("sellUntil", localDateTime(resultSet.getObject("sell_until")));
        return item;
    }

    private Map<String, Object> ledgerRow(ResultSet resultSet) throws SQLException {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("entryType", resultSet.getString("entry_type"));
        item.put("direction", resultSet.getString("direction"));
        item.put("creditMicros", resultSet.getLong("credit_micros"));
        item.put("balanceAfterMicros", resultSet.getLong("balance_after_micros"));
        item.put("sourceType", resultSet.getString("source_type"));
        item.put("sourceId", resultSet.getString("source_id"));
        item.put("occurredAt", localDateTime(resultSet.getObject("occurred_at")));
        item.put("expiresAt", localDateTime(resultSet.getObject("expires_at")));
        item.put("metadata", jsonMap(resultSet.getString("metadata_json")));
        return item;
    }

    private Map<String, Object> refundRow(ResultSet resultSet) throws SQLException {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("refundNo", resultSet.getString("refund_no"));
        response.put("orderNo", resultSet.getString("order_no"));
        response.put("status", resultSet.getString("status"));
        response.put("amount", amount(
                resultSet.getString("currency"),
                resultSet.getLong("amount_minor"),
                resultSet.getLong("amount_minor")
        ));
        response.put("reasonCode", resultSet.getString("reason_code"));
        response.put("reasonNote", resultSet.getString("reason_note"));
        response.put("product", jsonMap(resultSet.getString("product_snapshot")));
        response.put("succeededAt", localDateTime(resultSet.getObject("succeeded_at")));
        response.put("createdAt", localDateTime(resultSet.getObject("created_at")));
        return response;
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

    private void markOutboxProcessed(String aggregateId, String eventType) {
        jdbcTemplate.update(
                """
                UPDATE billing_outbox
                SET status = 'PROCESSED', processed_at = ?, last_error = NULL
                WHERE aggregate_type = 'ORDER' AND aggregate_id = ? AND event_type = ?
                """,
                LocalDateTime.now(),
                aggregateId,
                eventType
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
        jdbcTemplate.update(
                """
                INSERT INTO payment_reconciliation_item (
                    id, reconciliation_date, provider, payment_no, provider_transaction_id,
                    internal_amount_minor, provider_amount_minor, internal_status,
                    provider_status, difference_type, process_status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    internal_amount_minor = VALUES(internal_amount_minor),
                    provider_amount_minor = VALUES(provider_amount_minor),
                    internal_status = VALUES(internal_status),
                    provider_status = VALUES(provider_status),
                    process_status = VALUES(process_status),
                    updated_at = VALUES(updated_at)
                """,
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
    }

    private Map<String, Object> amount(String currency, long payable, long paid) {
        Map<String, Object> amount = new LinkedHashMap<>();
        amount.put("currency", normalizeCurrency(currency));
        amount.put("payableMinor", payable);
        amount.put("paidMinor", paid);
        amount.put("formatted", formatAmount(currency, payable));
        return amount;
    }

    private String formatAmount(String currency, long amountMinor) {
        BigDecimal value = BigDecimal.valueOf(amountMinor, 2);
        String symbol = "CNY".equals(normalizeCurrency(currency)) ? "¥" : normalizeCurrency(currency) + " ";
        return symbol + value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    private String snapshotName(Object value) {
        Object name = jsonMap(value).get("name");
        return name == null ? "Lyle 套餐" : String.valueOf(name);
    }

    private List<?> benefits(Object value) {
        Object benefits = jsonMap(value).get("benefits");
        return benefits instanceof List<?> list ? list : List.of();
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

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize billing snapshot.", exception);
        }
    }

    private static boolean isPaidAlipayNotification(Map<String, String> parameters) {
        String status = parameters.get("trade_status");
        return ("TRADE_SUCCESS".equals(status) || "TRADE_FINISHED".equals(status))
                && !StringUtils.hasText(parameters.get("out_biz_no"))
                && !StringUtils.hasText(parameters.get("gmt_refund"))
                && !StringUtils.hasText(parameters.get("refund_fee"));
    }

    private static String requiredNotificationField(
            Map<String, String> parameters,
            String field,
            int maxLength
    ) {
        String value = parameters.get(field);
        if (!StringUtils.hasText(value) || value.length() > maxLength) {
            throw badRequest("支付宝通知缺少必要字段。");
        }
        return value.trim();
    }

    private static long alipayAmountMinor(String value) {
        if (!StringUtils.hasText(value)) {
            throw badRequest("支付宝通知缺少支付金额。");
        }
        try {
            return new BigDecimal(value.trim()).movePointRight(2).longValueExact();
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
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(body)
            );
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private long aggregateLong(String sql, Object... arguments) {
        Long result = jdbcTemplate.queryForObject(sql, Long.class, arguments);
        return result == null ? 0 : result;
    }

    private static long positiveNumber(Object value, long fallback) {
        try {
            long result = number(value);
            return result > 0 ? result : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static long number(Object value) {
        if (value == null) return 0;
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private static Integer nullableInt(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.intValue();
        return Integer.parseInt(String.valueOf(value));
    }

    private static LocalDateTime localDateTime(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime dateTime) return dateTime;
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toLocalDateTime();
        if (value instanceof java.time.OffsetDateTime offsetDateTime) return offsetDateTime.toLocalDateTime();
        return LocalDateTime.parse(String.valueOf(value).replace(" ", "T"));
    }

    private static String businessNo(String prefix) {
        return prefix
                + LocalDateTime.now(ZoneOffset.UTC).format(BUSINESS_NO_TIME)
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
    }

    private static long nextId() {
        return ID_SEQUENCE.incrementAndGet();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeNullable(String value) {
        return StringUtils.hasText(value) ? normalize(value) : null;
    }

    private static String normalizeCode(String value, int maxLength) {
        String result = normalize(value);
        if (!result.matches("[A-Z0-9][A-Z0-9._:-]*") || result.length() > maxLength) {
            throw badRequest("业务编码格式无效。");
        }
        return result;
    }

    private static String normalizeCurrency(String value) {
        String result = normalize(value);
        if (!result.matches("[A-Z]{3}")) {
            throw badRequest("币种代码无效。");
        }
        return result;
    }

    private static String allowedProductType(String value) {
        String result = normalize(value);
        if (!List.of("PLAN", "CREDIT_PACK").contains(result)) {
            throw badRequest("商品类型仅支持 PLAN 或 CREDIT_PACK。");
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
