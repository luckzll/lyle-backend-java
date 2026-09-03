package com.example.aiapp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.aiapp.domain.entity.CommercialLedgerEntryEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 商业账户流水数据访问接口。
 */
@Mapper
public interface CommercialLedgerEntryMapper extends BaseMapper<CommercialLedgerEntryEntity> {
}
