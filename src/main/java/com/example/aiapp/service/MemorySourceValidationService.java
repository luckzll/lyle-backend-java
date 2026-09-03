package com.example.aiapp.service;

import java.util.List;
import java.util.Map;

/**
 * 记忆来源校验服务。
 */
public interface MemorySourceValidationService {

    List<Map<String, Object>> validate(String rawUserId, List<Map<String, Object>> sources);
}
