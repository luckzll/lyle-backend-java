package com.example.aiapp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.aiapp.domain.entity.CommercialReservationEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 商业额度预留数据访问接口。
 */
@Mapper
public interface CommercialReservationMapper extends BaseMapper<CommercialReservationEntity> {
}
