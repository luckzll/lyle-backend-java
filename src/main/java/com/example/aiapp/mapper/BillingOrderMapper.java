package com.example.aiapp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.aiapp.domain.entity.BillingOrderEntity;
import com.example.aiapp.domain.vo.BillingOrderReadVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 计费订单数据访问接口。
 */
@Mapper
public interface BillingOrderMapper extends BaseMapper<BillingOrderEntity> {

    /** 查询用户最近的订单及其最新支付、退款状态。 */
    List<BillingOrderReadVO> selectUserOrders(
            @Param("userId") long userId,
            @Param("limit") int limit
    );

    /** 按订单号查询当前用户的订单详情。 */
    BillingOrderReadVO selectUserOrder(
            @Param("userId") long userId,
            @Param("orderNo") String orderNo
    );

    /** 按客户端幂等号查询订单号，可选行锁。 */
    String selectOrderNoByClientRequest(
            @Param("userId") long userId,
            @Param("clientRequestId") String clientRequestId,
            @Param("forUpdate") boolean forUpdate
    );

    /** 创建支付尝试记录。 */
    int insertPaymentAttempt(
            @Param("id") long id,
            @Param("paymentNo") String paymentNo,
            @Param("orderId") long orderId,
            @Param("provider") String provider,
            @Param("currency") String currency,
            @Param("amountMinor") long amountMinor,
            @Param("expiresAt") LocalDateTime expiresAt,
            @Param("createdAt") LocalDateTime createdAt
    );

    /** 将订单置为待支付。 */
    int markOrderPendingPayment(@Param("orderId") long orderId, @Param("updatedAt") LocalDateTime updatedAt);

    /** 将支付尝试置为成功。 */
    int markPaymentSucceeded(
            @Param("paymentId") long paymentId,
            @Param("providerTransactionId") String providerTransactionId,
            @Param("succeededAt") LocalDateTime succeededAt
    );

    /** 将订单置为已支付。 */
    int markOrderPaid(@Param("orderId") long orderId, @Param("paidAt") LocalDateTime paidAt);

    /** 标记支付回调已处理。 */
    int updateWebhookProcessed(
            @Param("provider") String provider,
            @Param("eventId") String eventId,
            @Param("processedAt") LocalDateTime processedAt
    );

    /** 标记支付回调处理失败。 */
    int updateWebhookFailed(
            @Param("provider") String provider,
            @Param("eventId") String eventId,
            @Param("processedAt") LocalDateTime processedAt,
            @Param("errorCode") String errorCode
    );

    /** 查询已过期的待支付订单号。 */
    List<String> selectExpiredPendingOrderNumbers(@Param("limit") int limit);

    /** 查询用户商业账户，可选行锁。 */
    Map<String, Object> selectAccountByUserId(
            @Param("userId") long userId,
            @Param("forUpdate") boolean forUpdate
    );

    /** 幂等创建用户商业账户。 */
    int insertAccountIgnore(
            @Param("id") long id,
            @Param("userId") long userId,
            @Param("createdAt") LocalDateTime createdAt
    );

    /** 查询用户当前生效主套餐。 */
    Map<String, Object> selectCurrentPlan(@Param("userId") long userId);

    /** 查询当前可售价格版本及商品信息。 */
    Map<String, Object> selectSellablePrice(@Param("versionCode") String versionCode);

    /** 查询当前用户订单及最新支付、退款状态。 */
    Map<String, Object> selectOrderForUser(
            @Param("userId") long userId,
            @Param("orderNo") String orderNo,
            @Param("forUpdate") boolean forUpdate
    );

    /** 按订单号查询原始订单，可选行锁。 */
    Map<String, Object> selectOrderMapByOrderNo(
            @Param("orderNo") String orderNo,
            @Param("forUpdate") boolean forUpdate
    );

    /** 按支付单号查询支付与订单上下文，可选行锁。 */
    Map<String, Object> selectPaymentByPaymentNo(
            @Param("paymentNo") String paymentNo,
            @Param("forUpdate") boolean forUpdate
    );

    /** 查询订单最近一次支付尝试。 */
    Map<String, Object> selectLatestPaymentByOrderId(@Param("orderId") long orderId);

    /** 关闭尚未完成的支付尝试。 */
    int markPaymentClosed(@Param("paymentId") long paymentId, @Param("closedAt") LocalDateTime closedAt);

    /** 关闭尚未完成支付的订单。 */
    int markOrderClosed(@Param("orderId") long orderId, @Param("closedAt") LocalDateTime closedAt);

    /** 幂等写入计费发件箱事件。 */
    int insertOutbox(
            @Param("id") long id,
            @Param("aggregateType") String aggregateType,
            @Param("aggregateId") String aggregateId,
            @Param("eventType") String eventType,
            @Param("eventVersion") int eventVersion,
            @Param("payloadJson") String payloadJson,
            @Param("createdAt") LocalDateTime createdAt
    );

    /** 幂等记录支付渠道回调。 */
    int insertWebhook(
            @Param("id") long id,
            @Param("provider") String provider,
            @Param("providerEventId") String providerEventId,
            @Param("payloadHash") String payloadHash,
            @Param("signatureValid") boolean signatureValid,
            @Param("processStatus") String processStatus,
            @Param("paymentNo") String paymentNo,
            @Param("receivedAt") LocalDateTime receivedAt,
            @Param("errorCode") String errorCode
    );

    /** 关闭订单下所有尚未完成的支付尝试。 */
    int closePaymentsByOrder(@Param("orderId") long orderId, @Param("closedAt") LocalDateTime closedAt);
}
