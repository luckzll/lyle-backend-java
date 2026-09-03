package com.example.aiapp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.aiapp.domain.entity.EntitlementGrantEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户权益数据访问接口。
 */
@Mapper
public interface EntitlementGrantMapper extends BaseMapper<EntitlementGrantEntity> {
}
