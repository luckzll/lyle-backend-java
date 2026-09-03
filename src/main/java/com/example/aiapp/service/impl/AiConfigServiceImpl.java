package com.example.aiapp.service.impl;

import com.example.aiapp.domain.entity.AiConfigEntity;
import com.example.aiapp.mapper.AiConfigMapper;
import com.example.aiapp.service.AiConfigService;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI 配置查询服务实现。
 */
@Service
public class AiConfigServiceImpl implements AiConfigService {

    private final AiConfigMapper aiConfigMapper;

    public AiConfigServiceImpl(AiConfigMapper aiConfigMapper) {
        this.aiConfigMapper = aiConfigMapper;
    }

    @Override
    public Map<String, String> currentConfigMap() {
        return aiConfigMapper.selectAllOrderByConfigKey()
                .stream()
                .collect(Collectors.toMap(AiConfigEntity::getConfigKey, AiConfigEntity::getConfigValue));
    }
}
