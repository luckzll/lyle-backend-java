package com.example.aiapp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.aiapp.domain.entity.RefundOrderEntity;
import com.example.aiapp.domain.vo.BillingRefundReadVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 退款单数据访问接口。
 */
@Mapper
public interface RefundOrderMapper extends BaseMapper<RefundOrderEntity> {
    Long lockOrderAccount(@Param("orderNo") String orderNo);

    /** 查询用户的退款记录及对应订单快照。 */
    List<BillingRefundReadVO> selectUserRefunds(@Param("userId") long userId);

    /** 查询需要向支付渠道轮询的退款单号。 */
    List<String> selectPendingRefundNumbers(@Param("limit") int limit);

    /** 按订单号查询退款所需订单信息，可选行锁。 */
    Map<String, Object> selectOrderByOrderNo(
            @Param("orderNo") String orderNo,
            @Param("forUpdate") boolean forUpdate
    );

    /** 按订单和幂等请求号查询退款单号。 */
    String selectRefundNoByOrderIdAndRequestId(
            @Param("orderId") long orderId,
            @Param("requestId") String requestId
    );

    /** 汇总订单处理中及已成功的退款金额。 */
    long sumActiveRefundAmount(@Param("orderId") long orderId);

    /** 汇总订单额度批次已消耗的额度。 */
    long sumConsumedGrantByOrderNo(@Param("orderNo") String orderNo);

    /** 刷新退款单更新时间。 */
    int touchRefund(@Param("id") long id, @Param("updatedAt") LocalDateTime updatedAt);

    /** 将订单置为退款中。 */
    int markOrderRefunding(@Param("orderId") long orderId, @Param("updatedAt") LocalDateTime updatedAt);

    /** 将退款单置为渠道处理中。 */
    int markRefundProcessing(@Param("id") long id, @Param("updatedAt") LocalDateTime updatedAt);

    /** 将退款单置为失败。 */
    int markRefundFailed(@Param("id") long id, @Param("updatedAt") LocalDateTime updatedAt);

    /** 锁定订单发放的额度批次。 */
    List<Map<String, Object>> selectOrderGrantsForUpdate(@Param("orderNo") String orderNo);

    /** 撤销额度批次并清空剩余额度。 */
    int revokeGrantAndClear(@Param("id") long id, @Param("updatedAt") LocalDateTime updatedAt);

    /** 撤销已无剩余额度的批次。 */
    int revokeGrant(@Param("id") long id, @Param("updatedAt") LocalDateTime updatedAt);

    /** 写入退款对应的额度冲正流水。 */
    int insertRefundReversal(
            @Param("id") long id,
            @Param("accountId") long accountId,
            @Param("grantId") long grantId,
            @Param("creditMicros") long creditMicros,
            @Param("balanceAfterMicros") long balanceAfterMicros,
            @Param("refundNo") String refundNo,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("occurredAt") LocalDateTime occurredAt,
            @Param("expiresAt") Object expiresAt,
            @Param("metadataJson") String metadataJson,
            @Param("createdAt") LocalDateTime createdAt
    );

    /** 将退款单置为成功。 */
    int markRefundSucceeded(
            @Param("id") long id,
            @Param("providerRefundId") String providerRefundId,
            @Param("succeededAt") LocalDateTime succeededAt,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    /** 将订单置为已退款。 */
    int markOrderRefunded(@Param("orderId") long orderId, @Param("updatedAt") LocalDateTime updatedAt);

    /** 按支付单号查询退款所需支付信息。 */
    Map<String, Object> selectPaymentByPaymentNo(@Param("paymentNo") String paymentNo);

    /** 按退款单号查询退款信息，可选行锁。 */
    Map<String, Object> selectRefundByRefundNo(
            @Param("refundNo") String refundNo,
            @Param("forUpdate") boolean forUpdate
    );

    /** 查询订单最近一次成功支付。 */
    Map<String, Object> selectLatestPaymentByOrderId(@Param("orderId") long orderId);

    /** 汇总账户当前可用额度。 */
    long sumAvailableCredits(@Param("accountId") long accountId);
}
