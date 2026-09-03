package com.example.aiapp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.aiapp.domain.entity.PaymentAttemptEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 支付尝试数据访问接口。
 */
@Mapper
public interface PaymentAttemptMapper extends BaseMapper<PaymentAttemptEntity> {
}
