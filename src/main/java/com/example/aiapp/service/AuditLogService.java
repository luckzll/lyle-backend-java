package com.example.aiapp.service;

public interface AuditLogService {

    void record(Long userId, String action, String target, String targetId);
}
