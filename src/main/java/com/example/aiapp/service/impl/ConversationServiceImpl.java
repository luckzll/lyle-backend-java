package com.example.aiapp.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.aiapp.domain.dto.CreateConversationDTO;
import com.example.aiapp.domain.dto.UpdateConversationDTO;
import com.example.aiapp.domain.entity.ConversationEntity;
import com.example.aiapp.domain.vo.ConversationVO;
import com.example.aiapp.domain.vo.PageVO;
import com.example.aiapp.mapper.ChatMessageMapper;
import com.example.aiapp.mapper.ConversationMapper;
import com.example.aiapp.service.AuditLogService;
import com.example.aiapp.service.ConversationService;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 用户会话服务实现。
 */
@Service
public class ConversationServiceImpl implements ConversationService {

    private final ConversationMapper conversationMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final AuditLogService auditLogService;

    public ConversationServiceImpl(
            ConversationMapper conversationMapper,
            ChatMessageMapper chatMessageMapper,
            AuditLogService auditLogService
    ) {
        this.conversationMapper = conversationMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.auditLogService = auditLogService;
    }

    @Override
    public ConversationVO create(Long userId, CreateConversationDTO request) {
        LocalDateTime now = LocalDateTime.now();
        ConversationEntity entity = new ConversationEntity();
        entity.setUserId(userId);
        entity.setTitle(request.title());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        conversationMapper.insert(entity);
        auditLogService.record(userId, "create", "conversation", String.valueOf(entity.getId()));
        return toVO(entity);
    }

    @Override
    public List<ConversationVO> list(Long userId) {
        return conversationMapper.selectByUserId(userId)
                .stream()
                .map(this::toVO)
                .toList();
    }

    @Override
    public PageVO<ConversationVO> page(Long userId, long page, long size) {
        IPage<ConversationEntity> result = conversationMapper.selectPageByUserId(Page.of(page, size), userId);
        return new PageVO<>(
                result.getRecords().stream().map(this::toVO).toList(),
                result.getTotal(),
                result.getCurrent(),
                result.getSize()
        );
    }

    @Override
    public ConversationVO updateTitle(Long userId, Long conversationId, UpdateConversationDTO request) {
        ConversationEntity entity = conversationMapper.selectByIdAndUserId(conversationId, userId);
        if (entity == null) {
            throw new ResponseStatusException(NOT_FOUND, "会话不存在");
        }
        entity.setTitle(request.title().trim());
        entity.setUpdatedAt(LocalDateTime.now());
        conversationMapper.updateById(entity);
        auditLogService.record(userId, "update", "conversation", String.valueOf(conversationId));
        return toVO(entity);
    }

    @Override
    public void delete(Long userId, Long conversationId) {
        ConversationEntity entity = conversationMapper.selectByIdAndUserId(conversationId, userId);
        if (entity == null) {
            throw new ResponseStatusException(NOT_FOUND, "会话不存在");
        }
        chatMessageMapper.deleteByConversationId(String.valueOf(conversationId));
        conversationMapper.deleteById(conversationId);
        auditLogService.record(userId, "delete", "conversation", String.valueOf(conversationId));
    }

    private ConversationVO toVO(ConversationEntity entity) {
        return new ConversationVO(
                entity.getId(),
                entity.getTitle(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
