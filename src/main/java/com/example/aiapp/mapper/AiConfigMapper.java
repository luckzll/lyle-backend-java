package com.example.aiapp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.aiapp.domain.entity.AiConfigEntity;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * AI 配置数据访问接口。
 */
@Mapper
public interface AiConfigMapper extends BaseMapper<AiConfigEntity> {

    /**
     * 按配置键升序查询全部 AI 配置。
     *
     * @return AI 配置列表
     */
    List<AiConfigEntity> selectAllOrderByConfigKey();
}
