package com.example.aiapp.service;

import com.example.aiapp.domain.dto.AiConfigUpdateDTO;
import com.example.aiapp.domain.vo.AiConfigItemVO;
import com.example.aiapp.domain.vo.AdminOverviewVO;
import com.example.aiapp.domain.vo.AuditLogVO;
import com.example.aiapp.domain.vo.PageVO;

import java.util.List;
import java.util.Map;

public interface AdminService {

    AdminOverviewVO overview();

    PageVO<AuditLogVO> auditLogs(long page, long size);

    List<AiConfigItemVO> aiConfigs();

    AiConfigItemVO updateAiConfig(Long userId, AiConfigUpdateDTO request);

    Map<String, Object> aiMetrics();

    Map<String, Object> experienceAnalytics(int days);

    Map<String, Object> aiTools();

    Map<String, Object> toolAudit(int limit);
}
