package com.example.aiapp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.aiapp.domain.entity.CommercialAccountEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 商业账户数据访问接口。
 */
@Mapper
public interface CommercialAccountMapper extends BaseMapper<CommercialAccountEntity> {

    /** 按用户查询商业账户，可选行锁。 */
    Map<String, Object> selectAccountByUserId(@Param("userId") long userId, @Param("forUpdate") boolean forUpdate);

    /** 幂等创建商业账户。 */
    int insertAccountIgnore(@Param("id") long id, @Param("userId") long userId,@Param("createdAt") LocalDateTime createdAt);

    /** 锁定商业账户，序列化额度预留操作。 */
    Long lockAccountById(@Param("accountId") long accountId);

    /** 汇总账户累计授予额度。 */
    Long sumGrantedCredits(@Param("accountId") long accountId);

    /** 汇总账户累计消耗额度。 */
    Long sumConsumedCredits(@Param("accountId") long accountId);

    Long sumAdminDeductedCredits(@Param("accountId") long accountId);

    /** 查询当前可用额度。 */
    Long sumAvailableCredits(@Param("accountId") long accountId);

    /** 查询最近到期时间。 */
    LocalDateTime selectNextExpiryAt(@Param("accountId") long accountId);

    /** 查询账户流水。 */
    List<Map<String, Object>> selectLedger(@Param("accountId") long accountId,@Param("entryType") String entryType,@Param("limit") int limit);

    /** 查询待履约订单号。 */
    List<String> selectPendingFulfillmentOrders(@Param("limit") int limit);

    /** 查询已到期但尚未处理的额度批次。 */
    List<Long> selectExpiredGrantIds(@Param("limit") int limit);

    /** 查询额度批次，可选行锁。 */
    Map<String, Object> selectGrantById(@Param("grantId") long grantId,
                                         @Param("forUpdate") boolean forUpdate);

    /** 将额度批次置为已过期。 */
    int expireGrant(@Param("grantId") long grantId, @Param("updatedAt") LocalDateTime updatedAt);

    /** 写入额度到期流水。 */
    int insertExpirationLedger(@Param("id") long id, @Param("accountId") long accountId,
                               @Param("grantId") long grantId, @Param("creditMicros") long creditMicros,
                               @Param("balanceAfterMicros") long balanceAfterMicros,
                               @Param("sourceId") String sourceId, @Param("idempotencyKey") String idempotencyKey,
                               @Param("occurredAt") LocalDateTime occurredAt, @Param("expiresAt") Object expiresAt,
                               @Param("metadataJson") String metadataJson);

    /** 按订单号查询订单，可选行锁。 */
    Map<String, Object> selectOrderByOrderNo(@Param("orderNo") String orderNo,
                                              @Param("forUpdate") boolean forUpdate);

    /** 将订单置为履约中。 */
    int markOrderFulfilling(@Param("orderId") long orderId, @Param("updatedAt") LocalDateTime updatedAt);

    /** 将订单置为已履约。 */
    int markOrderFulfilled(@Param("orderId") long orderId, @Param("fulfilledAt") LocalDateTime fulfilledAt);

    /** 标记履约事件已处理。 */
    int markOutboxProcessed(@Param("aggregateId") String aggregateId,
                            @Param("eventType") String eventType,
                            @Param("processedAt") LocalDateTime processedAt);

    /** 记录履约失败并安排重试。 */
    int markFulfillmentFailed(@Param("orderNo") String orderNo, @Param("lastError") String lastError);

    /** 汇总仍处于生效状态的预留额度。 */
    Long sumActiveReservationCredits(@Param("accountId") long accountId);

    /** 创建商业额度预留。 */
    int insertReservation(@Param("id") String id, @Param("accountId") long accountId,
                          @Param("userId") long userId, @Param("turnId") String turnId,
                          @Param("reservedCreditMicros") long reservedCreditMicros,
                          @Param("expiresAt") LocalDateTime expiresAt, @Param("createdAt") LocalDateTime createdAt);

    /** 释放尚未结算的预留。 */
    int releaseReservation(@Param("id") String id, @Param("updatedAt") LocalDateTime updatedAt);

    /** 幂等写入待计费使用记录。 */
    int insertBillableUsage(@Param("id") long id, @Param("usageEventId") String usageEventId,
                            @Param("userId") long userId, @Param("accountId") long accountId,
                            @Param("pricingVersionCode") String pricingVersionCode,
                            @Param("chargeCreditMicros") long chargeCreditMicros,
                            @Param("usageSource") String usageSource, @Param("status") String status,
                            @Param("reservationId") String reservationId,
                            @Param("metadataJson") String metadataJson, @Param("createdAt") LocalDateTime createdAt);

    /** 完成影子计费记录。 */
    int markUsageShadowSettled(@Param("usageEventId") String usageEventId,
                               @Param("settledAt") LocalDateTime settledAt);

    /** 标记额度不足的计费记录。 */
    int markUsageInsufficient(@Param("usageEventId") String usageEventId,
                              @Param("settledAt") LocalDateTime settledAt);

    /** 标记计费记录已结算。 */
    int markUsageSettled(@Param("usageEventId") String usageEventId, @Param("ledgerEntryId") long ledgerEntryId,
                         @Param("settledAt") LocalDateTime settledAt);

    /** 将预留额度结算到实际消耗。 */
    int settleReservation(@Param("id") String id, @Param("chargeCreditMicros") long chargeCreditMicros,
                          @Param("updatedAt") LocalDateTime updatedAt);

    /** 查询尚未生成计费记录的成功用量事件。 */
    List<String> selectPendingUsageEventIds(@Param("limit") int limit);

    /** 按事件编号查询 AI 用量事件。 */
    Map<String, Object> selectUsageEvent(@Param("eventId") String eventId);

    /** 按版本号查询商业计价规则。 */
    Map<String, Object> selectUsagePricingByVersion(@Param("versionCode") String versionCode);

    /** 发布商业计价规则。 */
    int insertUsagePricing(@Param("id") long id, @Param("versionCode") String versionCode,
                           @Param("rulesJson") String rulesJson, @Param("effectiveFrom") LocalDateTime effectiveFrom,
                           @Param("effectiveUntil") LocalDateTime effectiveUntil,
                           @Param("createdAt") LocalDateTime createdAt);

    /** 按主键查询商业计价规则。 */
    Map<String, Object> selectUsagePricingById(@Param("id") long id);

    /** 幂等创建额度批次。 */
    int insertGrant(@Param("id") long id, @Param("accountId") long accountId,
                    @Param("grantType") String grantType, @Param("sourceType") String sourceType,
                    @Param("sourceId") String sourceId, @Param("entitlementCode") String entitlementCode,
                    @Param("priceVersionCode") String priceVersionCode, @Param("quantityMicros") long quantityMicros,
                    @Param("validFrom") LocalDateTime validFrom, @Param("expiresAt") LocalDateTime expiresAt,
                    @Param("metadataJson") String metadataJson, @Param("createdAt") LocalDateTime createdAt);

    /** 幂等写入额度授予流水。 */
    int insertCreditLedger(@Param("id") long id, @Param("accountId") long accountId,
                           @Param("grantId") long grantId, @Param("entryType") String entryType,
                           @Param("creditMicros") long creditMicros, @Param("balanceAfterMicros") long balanceAfterMicros,
                           @Param("sourceType") String sourceType, @Param("sourceId") String sourceId,
                           @Param("idempotencyKey") String idempotencyKey, @Param("occurredAt") LocalDateTime occurredAt,
                           @Param("expiresAt") LocalDateTime expiresAt, @Param("metadataJson") String metadataJson);

    /** 按到期顺序锁定可扣减额度批次。 */
    List<Map<String, Object>> selectSpendableGrantsForUpdate(@Param("accountId") long accountId);

    /** 原子扣减额度批次。 */
    int decrementGrant(@Param("grantId") long grantId, @Param("creditMicros") long creditMicros,
                       @Param("updatedAt") LocalDateTime updatedAt);

    /** 写入用量扣减流水。 */
    int insertUsageDebitLedger(@Param("id") long id, @Param("accountId") long accountId,
                               @Param("grantId") long grantId, @Param("creditMicros") long creditMicros,
                               @Param("balanceAfterMicros") long balanceAfterMicros,
                               @Param("usageEventId") String usageEventId,
                               @Param("idempotencyKey") String idempotencyKey,
                               @Param("occurredAt") LocalDateTime occurredAt, @Param("expiresAt") Object expiresAt,
                               @Param("metadataJson") String metadataJson);

    int insertAdminDebitLedger(@Param("id") long id, @Param("accountId") long accountId,
                              @Param("grantId") long grantId, @Param("creditMicros") long creditMicros,
                              @Param("balanceAfterMicros") long balanceAfterMicros,
                              @Param("requestId") String requestId, @Param("idempotencyKey") String idempotencyKey,
                              @Param("occurredAt") LocalDateTime occurredAt, @Param("expiresAt") Object expiresAt,
                              @Param("metadataJson") String metadataJson);

    /** 查询当前生效主套餐。 */
    Map<String, Object> selectCurrentPlan(@Param("userId") long userId);

    /** 查询账户主套餐最晚到期时间。 */
    LocalDateTime selectLatestPlanExpiry(@Param("accountId") long accountId);

    /** 查询当前生效商业计价规则。 */
    Map<String, Object> selectActiveUsagePricing();

    /** 查询指定对话回合的有效预留。 */
    String selectActiveReservationId(@Param("userId") long userId, @Param("turnId") String turnId);
}
