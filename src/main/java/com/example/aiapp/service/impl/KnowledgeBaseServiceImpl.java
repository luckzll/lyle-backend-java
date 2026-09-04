package com.example.aiapp.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.aiapp.domain.dto.CreateKnowledgeBaseDTO;
import com.example.aiapp.domain.dto.UpdateKnowledgeBaseDTO;
import com.example.aiapp.domain.entity.KnowledgeBaseEntity;
import com.example.aiapp.domain.vo.KnowledgeBaseVO;
import com.example.aiapp.domain.vo.PageVO;
import com.example.aiapp.mapper.KnowledgeBaseMapper;
import com.example.aiapp.mapper.KnowledgeFileMapper;
import com.example.aiapp.service.AuditLogService;
import com.example.aiapp.service.KnowledgeBaseService;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 用户知识库服务实现。
 */
@Service
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeFileMapper knowledgeFileMapper;
    private final AuditLogService auditLogService;

    public KnowledgeBaseServiceImpl(
            KnowledgeBaseMapper knowledgeBaseMapper,
            KnowledgeFileMapper knowledgeFileMapper,
            AuditLogService auditLogService
    ) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.knowledgeFileMapper = knowledgeFileMapper;
        this.auditLogService = auditLogService;
    }

    @Override
    public KnowledgeBaseVO create(Long userId, CreateKnowledgeBaseDTO request) {
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setUserId(userId);
        entity.setName(request.name());
        entity.setCreatedAt(LocalDateTime.now());
        knowledgeBaseMapper.insert(entity);
        auditLogService.record(userId, "create", "knowledge_base", String.valueOf(entity.getId()));
        return toVO(entity);
    }

    @Override
    public List<KnowledgeBaseVO> list(Long userId) {
        return knowledgeBaseMapper.selectByUserId(userId)
                .stream()
                .map(this::toVO)
                .toList();
    }

    @Override
    public PageVO<KnowledgeBaseVO> page(Long userId, long page, long size) {
        IPage<KnowledgeBaseEntity> result = knowledgeBaseMapper.selectPageByUserId(Page.of(page, size), userId);
        return new PageVO<>(
                result.getRecords().stream().map(this::toVO).toList(),
                result.getTotal(),
                result.getCurrent(),
                result.getSize()
        );
    }

    @Override
    public KnowledgeBaseVO updateName(Long userId, Long knowledgeBaseId, UpdateKnowledgeBaseDTO request) {
        KnowledgeBaseEntity entity = knowledgeBaseMapper.selectByIdAndUserId(knowledgeBaseId, userId);
        if (entity == null) {
            throw new ResponseStatusException(NOT_FOUND, "知识库不存在");
        }
        entity.setName(request.name().trim());
        knowledgeBaseMapper.updateById(entity);
        auditLogService.record(userId, "update", "knowledge_base", String.valueOf(knowledgeBaseId));
        return toVO(entity);
    }

    @Override
    public void delete(Long userId, Long knowledgeBaseId) {
        if (knowledgeBaseMapper.selectByIdAndUserId(knowledgeBaseId, userId) == null) {
            throw new ResponseStatusException(NOT_FOUND, "知识库不存在");
        }
        knowledgeFileMapper.deleteByKnowledgeBaseId(knowledgeBaseId);
        knowledgeBaseMapper.deleteById(knowledgeBaseId);
        auditLogService.record(userId, "delete", "knowledge_base", String.valueOf(knowledgeBaseId));
    }

    private KnowledgeBaseVO toVO(KnowledgeBaseEntity entity) {
        return new KnowledgeBaseVO(
                entity.getId(),
                entity.getName(),
                entity.getCreatedAt()
        );
    }
}
