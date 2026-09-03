package com.example.aiapp.service;

/**
 * 审计日志服务。
 */
public interface AuditLogService {

    void record(Long userId, String action, String target, String targetId);
}
