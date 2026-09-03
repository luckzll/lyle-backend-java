package com.example.aiapp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.aiapp.domain.entity.PaymentWebhookEventEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 支付回调事件数据访问接口。
 */
@Mapper
public interface PaymentWebhookEventMapper extends BaseMapper<PaymentWebhookEventEntity> {
}
