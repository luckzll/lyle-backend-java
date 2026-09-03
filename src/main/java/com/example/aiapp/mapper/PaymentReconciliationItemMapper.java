package com.example.aiapp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.aiapp.domain.entity.PaymentReconciliationItemEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 支付对账差异项数据访问接口。
 */
@Mapper
public interface PaymentReconciliationItemMapper extends BaseMapper<PaymentReconciliationItemEntity> {

    /** 按处理状态查询最近的对账记录。 */
    List<Map<String, Object>> selectRecent(
            @Param("status") String status,
            @Param("limit") int limit
    );

    /** 查询指定渠道和日期范围内的内部支付记录。 */
    List<Map<String, Object>> selectPaymentsForReconciliation(
            @Param("provider") String provider,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end
    );

    /** 完成对账差异处理。 */
    int resolveItem(
            @Param("id") long id,
            @Param("operatorUserId") long operatorUserId,
            @Param("note") String note,
            @Param("resolvedAt") LocalDateTime resolvedAt
    );

    /** 按主键查询对账差异详情。 */
    Map<String, Object> selectMapById(@Param("id") long id);

    /** 新增或更新支付对账差异。 */
    int upsertItem(
            @Param("id") long id,
            @Param("reconciliationDate") LocalDate reconciliationDate,
            @Param("provider") String provider,
            @Param("paymentNo") String paymentNo,
            @Param("providerTransactionId") String providerTransactionId,
            @Param("internalAmountMinor") Long internalAmountMinor,
            @Param("providerAmountMinor") Long providerAmountMinor,
            @Param("internalStatus") String internalStatus,
            @Param("providerStatus") String providerStatus,
            @Param("differenceType") String differenceType,
            @Param("processStatus") String processStatus,
            @Param("createdAt") LocalDateTime createdAt,
            @Param("updatedAt") LocalDateTime updatedAt
    );
}
