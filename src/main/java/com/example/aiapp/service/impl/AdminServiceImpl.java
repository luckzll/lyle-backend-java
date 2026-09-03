package com.example.aiapp.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.aiapp.domain.dto.AiConfigUpdateDTO;
import com.example.aiapp.domain.entity.AiConfigEntity;
import com.example.aiapp.domain.entity.AuditLogEntity;
import com.example.aiapp.domain.vo.AiConfigItemVO;
import com.example.aiapp.domain.vo.AdminOverviewVO;
import com.example.aiapp.domain.vo.AuditLogVO;
import com.example.aiapp.domain.vo.PageVO;
import com.example.aiapp.infrastructure.client.AiServiceClient;
import com.example.aiapp.mapper.AiConfigMapper;
import com.example.aiapp.mapper.AuditLogMapper;
import com.example.aiapp.mapper.ConversationMapper;
import com.example.aiapp.mapper.KnowledgeBaseMapper;
import com.example.aiapp.mapper.KnowledgeFileMapper;
import com.example.aiapp.mapper.UserMapper;
import com.example.aiapp.service.AdminService;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 管理后台业务服务实现。
 */
@Service
public class AdminServiceImpl implements AdminService {

    private final AiConfigMapper aiConfigMapper;
    private final UserMapper userMapper;
    private final ConversationMapper conversationMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeFileMapper knowledgeFileMapper;
    private final AuditLogMapper auditLogMapper;
    private final AiServiceClient aiServiceClient;

    public AdminServiceImpl(
            AiConfigMapper aiConfigMapper,
            UserMapper userMapper,
            ConversationMapper conversationMapper,
            KnowledgeBaseMapper knowledgeBaseMapper,
            KnowledgeFileMapper knowledgeFileMapper,
            AuditLogMapper auditLogMapper,
            AiServiceClient aiServiceClient
    ) {
        this.aiConfigMapper = aiConfigMapper;
        this.userMapper = userMapper;
        this.conversationMapper = conversationMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.knowledgeFileMapper = knowledgeFileMapper;
        this.auditLogMapper = auditLogMapper;
        this.aiServiceClient = aiServiceClient;
    }

    @Override
    public AdminOverviewVO overview() {
        return new AdminOverviewVO(
                userMapper.selectCount(null),
                conversationMapper.selectCount(null),
                knowledgeBaseMapper.selectCount(null),
                knowledgeFileMapper.selectCount(null),
                auditLogMapper.selectCount(null)
        );
    }

    @Override
    public PageVO<AuditLogVO> auditLogs(long page, long size) {
        IPage<AuditLogEntity> result = auditLogMapper.selectPageOrderByCreatedAtDesc(Page.of(page, size));
        return new PageVO<>(
                result.getRecords().stream().map(this::toVO).toList(),
                result.getTotal(),
                result.getCurrent(),
                result.getSize()
        );
    }

    @Override
    public List<AiConfigItemVO> aiConfigs() {
        return aiConfigMapper.selectAllOrderByConfigKey().stream().map(this::toVO).toList();
    }

    @Override
    public AiConfigItemVO updateAiConfig(Long userId, AiConfigUpdateDTO request) {
        AiConfigEntity entity = aiConfigMapper.selectById(request.key());
        if (entity == null) {
            throw new ResponseStatusException(NOT_FOUND, "AI 配置不存在");
        }
        entity.setConfigValue(request.value());
        entity.setUpdatedAt(LocalDateTime.now());
        aiConfigMapper.updateById(entity);
        saveAuditLog(userId, "UPDATE_AI_CONFIG", "AI_CONFIG", request.key());
        return toVO(entity);
    }

    @Override
    public Map<String, Object> aiMetrics() {
        return aiServiceClient.getMetrics();
    }

    @Override
    public Map<String, Object> experienceAnalytics(int days) {
        return aiServiceClient.getExperienceAnalytics(Math.max(1, Math.min(days, 90)));
    }

    @Override
    public Map<String, Object> aiTools() {
        return aiServiceClient.getTools();
    }

    @Override
    public Map<String, Object> toolAudit(int limit) {
        return aiServiceClient.getToolAudit(limit);
    }

    private AiConfigItemVO toVO(AiConfigEntity entity) {
        return new AiConfigItemVO(
                entity.getConfigKey(),
                entity.getConfigValue(),
                entity.getDescription(),
                entity.getUpdatedAt()
        );
    }

    private void saveAuditLog(Long userId, String action, String target, String targetId) {
        AuditLogEntity entity = new AuditLogEntity();
        entity.setUserId(userId);
        entity.setAction(action);
        entity.setTarget(target);
        entity.setTargetId(targetId);
        entity.setCreatedAt(LocalDateTime.now());
        auditLogMapper.insert(entity);
    }

    private AuditLogVO toVO(AuditLogEntity entity) {
        return new AuditLogVO(
                entity.getId(),
                entity.getUserId(),
                entity.getAction(),
                entity.getTarget(),
                entity.getTargetId(),
                entity.getCreatedAt()
        );
    }
}
