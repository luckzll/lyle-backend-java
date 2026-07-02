package com.example.aiapp.service.impl;

import com.example.aiapp.domain.entity.AuditLogEntity;
import com.example.aiapp.mapper.AuditLogMapper;
import com.example.aiapp.service.AuditLogService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class AuditLogServiceImpl implements AuditLogService {

    private final AuditLogMapper auditLogMapper;

    public AuditLogServiceImpl(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    @Override
    public void record(Long userId, String action, String target, String targetId) {
        AuditLogEntity entity = new AuditLogEntity();
        entity.setUserId(userId);
        entity.setAction(action);
        entity.setTarget(target);
        entity.setTargetId(targetId);
        entity.setCreatedAt(LocalDateTime.now());
        auditLogMapper.insert(entity);
    }
}
