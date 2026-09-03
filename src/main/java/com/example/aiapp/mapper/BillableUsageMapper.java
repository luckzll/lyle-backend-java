package com.example.aiapp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.aiapp.domain.entity.BillableUsageEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 可计费用量数据访问接口。
 */
@Mapper
public interface BillableUsageMapper extends BaseMapper<BillableUsageEntity> {
}
