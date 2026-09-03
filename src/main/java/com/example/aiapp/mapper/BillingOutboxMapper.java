package com.example.aiapp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.aiapp.domain.entity.BillingOutboxEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 计费事件发件箱数据访问接口。
 */
@Mapper
public interface BillingOutboxMapper extends BaseMapper<BillingOutboxEntity> {
}
