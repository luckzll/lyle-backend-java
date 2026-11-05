package com.example.aiapp.service.impl;

import com.example.aiapp.billing.BillingMetrics;
import com.example.aiapp.billing.BillingProperties;
import com.example.aiapp.billing.BillingRequests;
import com.example.aiapp.mapper.CommercialAccountMapper;
import com.example.aiapp.service.BillingAccountService;
import com.example.aiapp.usage.UsageEventRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.example.aiapp.billing.support.BillingIdentifiers.nextId;

/**
 * 商业账户与额度服务实现。
 */
@Service
public class BillingAccountServiceImpl implements BillingAccountService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final CommercialAccountMapper commercialAccountMapper;
    private final ObjectMapper objectMapper;
    private final BillingProperties properties;
    private final BillingMetrics metrics;

    public BillingAccountServiceImpl(
            CommercialAccountMapper commercialAccountMapper,
            ObjectMapper objectMapper,
            BillingProperties properties,
            BillingMetrics metrics
    ) {
        this.commercialAccountMapper = commercialAccountMapper;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
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
        response.put("totalGrantedCreditMicros", zeroIfNull(
                commercialAccountMapper.sumGrantedCredits(accountId)
        ));
        response.put("totalConsumedCreditMicros", zeroIfNull(
                commercialAccountMapper.sumConsumedCredits(accountId)
        ));
        response.put("nextExpiryAt", commercialAccountMapper.selectNextExpiryAt(accountId));
        response.put("commercialQuotaEnforced", properties.quotaEnforcementEnabled());
        response.put("settlementMode", properties.settlementShadowEnabled() ? "SHADOW" : "ACTIVE");
        response.put("checkoutEnabled", properties.checkoutEnabled());
        return response;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<Map<String, Object>> ledger(long userId, String type, int limit) {
        require(properties.userViewEnabled() && properties.ledgerEnabled());
        long accountId = number(ensureAccount(userId).get("id"));
        grantWelcomeCredits(accountId, userId);
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        String normalizedType = normalizeNullable(type);
        return commercialAccountMapper.selectLedger(accountId, normalizedType, boundedLimit)
                .stream()
                .map(this::ledgerRow)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> pendingFulfillmentOrders(int limit) {
        if (!properties.fulfillmentWorkerEnabled()) {
            return List.of();
        }
        return commercialAccountMapper.selectPendingFulfillmentOrders(
                Math.max(1, Math.min(limit, 50))
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> expiredGrantIds(int limit) {
        if (!properties.ledgerEnabled()) {
            return List.of();
        }
        return commercialAccountMapper.selectExpiredGrantIds(
                Math.max(1, Math.min(limit, 200))
        );
    }

    @Override
    @Transactional
    public void expireGrant(long grantId) {
        Map<String, Object> grant = commercialAccountMapper.selectGrantById(grantId, true);
        if (grant == null) {
            return;
        }
        long remaining = number(grant.get("remaining_micros"));
        LocalDateTime expiresAt = localDateTime(grant.get("expires_at"));
        if (!"ACTIVE".equals(normalize(String.valueOf(grant.get("status"))))
                || remaining <= 0
                || expiresAt == null
                || expiresAt.isAfter(LocalDateTime.now())) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        commercialAccountMapper.expireGrant(grantId, now);
        long accountId = number(grant.get("account_id"));
        commercialAccountMapper.insertExpirationLedger(
                nextId(),
                accountId,
                grantId,
                remaining,
                availableCredits(accountId),
                String.valueOf(grantId),
                safe("EXPIRATION:" + grantId, 128),
                now,
                grant.get("expires_at"),
                json(Map.of())
        );
    }

    @Override
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
        commercialAccountMapper.markOrderFulfilling(number(order.get("id")), now);
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
        commercialAccountMapper.markOrderFulfilled(number(order.get("id")), now);
        markOutboxProcessed(orderNo, "FULFILL_ORDER");
        LocalDateTime paidAt = localDateTime(order.get("paid_at"));
        metrics.orderFulfilled(paidAt == null ? null : Duration.between(paidAt, now));
    }

    @Override
    public void markFulfillmentFailed(String orderNo, Exception exception) {
        String message = exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
        commercialAccountMapper.markFulfillmentFailed(orderNo, safe(message, 512));
    }

    @Override
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
        commercialAccountMapper.lockAccountById(accountId);
        long available = availableCredits(accountId);
        long active = zeroIfNull(commercialAccountMapper.sumActiveReservationCredits(accountId));
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
        commercialAccountMapper.insertReservation(
                reservationId,
                accountId,
                userId,
                safeNullable(turnId, 128),
                reserve,
                now.plusMinutes(Math.max(1, properties.reservationTtlMinutes())),
                now
        );
        return reservationId;
    }

    @Override
    public void releaseCommercialReservation(String reservationId) {
        if (!StringUtils.hasText(reservationId)) {
            return;
        }
        commercialAccountMapper.releaseReservation(reservationId, LocalDateTime.now());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void settleUsage(UsageEventRequest event) {
        settleUsageInternal(event);
    }

    private void settleUsageInternal(UsageEventRequest event) {
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
        int inserted = commercialAccountMapper.insertBillableUsage(
                nextId(),
                event.eventId(),
                userId,
                accountId,
                String.valueOf(pricing.get("version_code")),
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
            commercialAccountMapper.markUsageShadowSettled(event.eventId(), LocalDateTime.now());
            metrics.commercialInsufficient();
            return;
        }
        Long firstLedgerId = debitCredits(accountId, event.eventId(), charge, event);
        if (firstLedgerId == null) {
            commercialAccountMapper.markUsageInsufficient(event.eventId(), LocalDateTime.now());
            return;
        }
        commercialAccountMapper.markUsageSettled(
                event.eventId(),
                firstLedgerId,
                LocalDateTime.now()
        );
        if (reservationId != null) {
            commercialAccountMapper.settleReservation(
                    reservationId,
                    charge,
                    LocalDateTime.now()
            );
        }
        metrics.commercialDebit(charge);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> pendingUsageEventIds(int limit) {
        if (!properties.ledgerEnabled() || activeUsagePricing() == null) {
            return List.of();
        }
        return commercialAccountMapper.selectPendingUsageEventIds(
                Math.max(1, Math.min(limit, 200))
        );
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void settleUsageEvent(String eventId) {
        Map<String, Object> row = commercialAccountMapper.selectUsageEvent(safe(eventId, 64));
        if (row != null) {
            settleUsageInternal(usageEvent(row));
        }
    }

    @Override
    @Transactional
    public Map<String, Object> publishUsagePricing(BillingRequests.PublishUsagePricing request) {
        String versionCode = normalizeCode(request.versionCode(), 64);
        Map<String, Object> existing = commercialAccountMapper.selectUsagePricingByVersion(versionCode);
        if (existing != null) {
            return existing;
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
        commercialAccountMapper.insertUsagePricing(
                id,
                versionCode,
                json(request.rules()),
                effectiveFrom,
                request.effectiveUntil(),
                now
        );
        return commercialAccountMapper.selectUsagePricingById(id);
    }

    @Override
    @Transactional
    public Map<String, Object> grantManualCredits(
            long userId,
            BillingRequests.GrantCredits request
    ) {
        grantAdminCredits(userId,request);
        return account(userId);
    }

    @Override
    @Transactional
    public void grantAdminCredits(long userId,BillingRequests.GrantCredits request) {
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
    }

    private Map<String, Object> ensureAccount(long userId) {
        Map<String, Object> existing = commercialAccountMapper.selectAccountByUserId(userId, false);
        if (existing != null) {
            return existing;
        }
        LocalDateTime now = LocalDateTime.now();
        commercialAccountMapper.insertAccountIgnore(nextId(), userId, now);
        return commercialAccountMapper.selectAccountByUserId(userId, true);
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
        int inserted = commercialAccountMapper.insertGrant(
                grantId,
                accountId,
                normalize(grantType),
                normalize(sourceType),
                safe(sourceId, 128),
                normalizeCode(entitlementCode, 64),
                safeNullable(priceVersionCode, 64),
                quantityMicros,
                validFrom,
                expiresAt,
                json(metadata),
                now
        );
        if (inserted == 0) {
            return;
        }
        long balance = availableCredits(accountId);
        commercialAccountMapper.insertCreditLedger(
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
                json(metadata)
        );
    }

    private Long debitCredits(
            long accountId,
            String usageEventId,
            long charge,
            UsageEventRequest event
    ) {
        List<Map<String, Object>> grants =
                commercialAccountMapper.selectSpendableGrantsForUpdate(accountId);
        long available = grants.stream().mapToLong(row -> number(row.get("remaining_micros"))).sum();
        if (available < charge) {
            return null;
        }
        long remaining = charge;
        Long firstLedgerId = null;
        for (Map<String, Object> grant : grants) {
            if (remaining == 0) break;
            long debit = Math.min(remaining, number(grant.get("remaining_micros")));
            commercialAccountMapper.decrementGrant(
                    number(grant.get("id")),
                    debit,
                    LocalDateTime.now()
            );
            long ledgerId = nextId();
            if (firstLedgerId == null) firstLedgerId = ledgerId;
            long balance = availableCredits(accountId);
            LocalDateTime occurredAt = LocalDateTime.now();
            commercialAccountMapper.insertUsageDebitLedger(
                    ledgerId,
                    accountId,
                    number(grant.get("id")),
                    debit,
                    balance,
                    usageEventId,
                    safe("USAGE_DEBIT:" + usageEventId + ":" + grant.get("id"), 128),
                    occurredAt,
                    grant.get("expires_at"),
                    json(Map.of(
                            "callType", normalize(event.callType()),
                            "taskType", normalize(event.taskType()),
                            "traceId", event.traceId()
                    ))
            );
            remaining -= debit;
        }
        return firstLedgerId;
    }

    private long availableCredits(long accountId) {
        return zeroIfNull(commercialAccountMapper.sumAvailableCredits(accountId));
    }

    private Map<String, Object> currentPlan(long userId) {
        Map<String, Object> row = commercialAccountMapper.selectCurrentPlan(userId);
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

    private LocalDateTime latestPlanExpiry(long accountId) {
        return commercialAccountMapper.selectLatestPlanExpiry(accountId);
    }

    private Map<String, Object> activeUsagePricing() {
        return commercialAccountMapper.selectActiveUsagePricing();
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
        return commercialAccountMapper.selectActiveReservationId(userId, turnId);
    }

    private Map<String, Object> requireOrder(String orderNo, boolean forUpdate) {
        Map<String, Object> row = commercialAccountMapper.selectOrderByOrderNo(
                safe(orderNo, 64),
                forUpdate
        );
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return row;
    }

    private void markOutboxProcessed(String aggregateId, String eventType) {
        commercialAccountMapper.markOutboxProcessed(
                aggregateId,
                eventType,
                LocalDateTime.now()
        );
    }

    private Map<String, Object> ledgerRow(Map<String, Object> row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("entryType", row.get("entry_type"));
        item.put("direction", row.get("direction"));
        item.put("creditMicros", number(row.get("credit_micros")));
        item.put("balanceAfterMicros", number(row.get("balance_after_micros")));
        item.put("sourceType", row.get("source_type"));
        item.put("sourceId", row.get("source_id"));
        item.put("occurredAt", localDateTime(row.get("occurred_at")));
        item.put("expiresAt", localDateTime(row.get("expires_at")));
        item.put("metadata", jsonMap(row.get("metadata_json")));
        return item;
    }

    private UsageEventRequest usageEvent(Map<String, Object> row) {
        LocalDateTime occurredAt = localDateTime(row.get("occurred_at"));
        return new UsageEventRequest(
                String.valueOf(row.get("event_id")),
                String.valueOf(number(row.get("user_id"))),
                stringValue(row.get("conversation_id")),
                stringValue(row.get("turn_id")),
                stringValue(row.get("trace_id")),
                stringValue(row.get("reservation_id")),
                stringValue(row.get("call_type")),
                stringValue(row.get("task_type")),
                stringValue(row.get("provider")),
                stringValue(row.get("model")),
                stringValue(row.get("route_version")),
                number(row.get("input_tokens")),
                number(row.get("output_tokens")),
                number(row.get("cache_read_tokens")),
                number(row.get("cache_write_tokens")),
                stringValue(row.get("usage_source")),
                number(row.get("estimated_cost_micros")),
                stringValue(row.get("pricing_version")),
                stringValue(row.get("currency")),
                number(row.get("latency_millis")),
                stringValue(row.get("status")),
                stringValue(row.get("error_category")),
                occurredAt.atOffset(ZoneOffset.ofHours(8))
        );
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

    private static long zeroIfNull(Long value) {
        return value == null ? 0 : value;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
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
