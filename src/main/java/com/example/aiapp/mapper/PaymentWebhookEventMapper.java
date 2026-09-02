package com.example.aiapp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.aiapp.domain.entity.PaymentWebhookEventEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PaymentWebhookEventMapper extends BaseMapper<PaymentWebhookEventEntity> {
}
