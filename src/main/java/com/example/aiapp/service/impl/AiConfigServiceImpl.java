package com.example.aiapp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.aiapp.domain.entity.AiConfigEntity;
import com.example.aiapp.mapper.AiConfigMapper;
import com.example.aiapp.service.AiConfigService;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AiConfigServiceImpl implements AiConfigService {

    private final AiConfigMapper aiConfigMapper;

    public AiConfigServiceImpl(AiConfigMapper aiConfigMapper) {
        this.aiConfigMapper = aiConfigMapper;
    }

    @Override
    public Map<String, String> currentConfigMap() {
        return aiConfigMapper.selectList(new LambdaQueryWrapper<AiConfigEntity>())
                .stream()
                .collect(Collectors.toMap(AiConfigEntity::getConfigKey, AiConfigEntity::getConfigValue));
    }
}
