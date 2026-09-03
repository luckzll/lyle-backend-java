package com.example.aiapp.controller;

import com.example.aiapp.domain.dto.AiConfigUpdateDTO;
import com.example.aiapp.domain.vo.AiConfigItemVO;
import com.example.aiapp.domain.vo.AdminOverviewVO;
import com.example.aiapp.domain.vo.AuditLogVO;
import com.example.aiapp.domain.vo.PageVO;
import com.example.aiapp.service.AdminService;
import com.example.aiapp.utils.CurrentUserUtils;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理后台接口。
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/overview")
    AdminOverviewVO overview() {
        return adminService.overview();
    }

    @GetMapping("/audit-logs")
    PageVO<AuditLogVO> auditLogs(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size
    ) {
        return adminService.auditLogs(page, size);
    }

    @GetMapping("/ai/config")
    List<AiConfigItemVO> aiConfig() {
        return adminService.aiConfigs();
    }

    @PutMapping("/ai/config")
    AiConfigItemVO updateAiConfig(@Valid @RequestBody AiConfigUpdateDTO request) {
        return adminService.updateAiConfig(CurrentUserUtils.currentUserId(), request);
    }

    @GetMapping("/ai/metrics")
    Map<String, Object> aiMetrics() {
        return adminService.aiMetrics();
    }

    @GetMapping("/experience/analytics")
    Map<String, Object> experienceAnalytics(@RequestParam(defaultValue = "7") int days) {
        return adminService.experienceAnalytics(days);
    }

    @GetMapping("/ai/tools")
    Map<String, Object> aiTools() {
        return adminService.aiTools();
    }

    @GetMapping("/ai/tools/audit")
    Map<String, Object> toolAudit(@RequestParam(defaultValue = "50") int limit) {
        return adminService.toolAudit(limit);
    }
}
