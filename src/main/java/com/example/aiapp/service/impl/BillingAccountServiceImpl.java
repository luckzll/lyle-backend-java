package com.example.aiapp.service.impl;

import com.example.aiapp.billing.BillingMetrics;
import com.example.aiapp.billing.BillingProperties;
import com.example.aiapp.billing.BillingRequests;
import com.example.aiapp.service.BillingAccountService;
import com.example.aiapp.usage.UsageEventRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.example.aiapp.billing.support.BillingIdentifiers.nextId;

@Service
public class BillingAccountServiceImpl implements BillingAccountService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final BillingProperties properties;
    private final BillingMetrics metrics;

    public BillingAccountServiceImpl(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            BillingProperties properties,
            BillingMetrics metrics
    ) {
        this.jdbcTemplate = jdbcTemplate;
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

    @Override
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

    @Override
    @Transactional(readOnly = true)
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

    @Override
    @Transactional(readOnly = true)
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

    @Override
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
        LocalDateTime expiresAt = localDateTime(grant.get("expires_at"));
        if (!"ACTIVE".equals(normalize(String.valueOf(grant.get("status"))))
                || remaining <= 0
                || expiresAt == null
                || expiresAt.isAfter(LocalDateTime.now())) {
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

    @Override
    public void markFulfillmentFailed(String orderNo, Exception exception) {
        String message = exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
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

    @Override
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

    @Override
    @Transactional(readOnly = true)
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

    @Override
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
            settleUsageInternal(events.get(0));
        }
    }

    @Override
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

    @Override
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
