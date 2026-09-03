package com.example.aiapp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.aiapp.domain.entity.BillingUsagePricingVersionEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用量计价版本数据访问接口。
 */
@Mapper
public interface BillingUsagePricingVersionMapper extends BaseMapper<BillingUsagePricingVersionEntity> {
}
