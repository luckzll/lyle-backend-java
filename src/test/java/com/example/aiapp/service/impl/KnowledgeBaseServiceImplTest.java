package com.example.aiapp.service.impl;

import com.example.aiapp.domain.dto.UpdateKnowledgeBaseDTO;
import com.example.aiapp.domain.entity.KnowledgeBaseEntity;
import com.example.aiapp.mapper.KnowledgeBaseMapper;
import com.example.aiapp.mapper.KnowledgeFileMapper;
import com.example.aiapp.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeBaseServiceImplTest {

    @Test
    void ownerCanUpdateNameAndTheValueIsTrimmed() {
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        KnowledgeBaseServiceImpl service = new KnowledgeBaseServiceImpl(
                knowledgeBaseMapper,
                mock(KnowledgeFileMapper.class),
                auditLogService
        );
        KnowledgeBaseEntity entity = knowledgeBase(200L, 42L, "旧名称");
        when(knowledgeBaseMapper.selectByIdAndUserId(200L, 42L)).thenReturn(entity);

        var result = service.updateName(42L, 200L, new UpdateKnowledgeBaseDTO("  Java 开发资料  "));

        assertThat(result.name()).isEqualTo("Java 开发资料");
        verify(knowledgeBaseMapper).updateById(entity);
        verify(auditLogService).record(42L, "update", "knowledge_base", "200");
    }

    @Test
    void anotherUsersKnowledgeBaseIsNotUpdated() {
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        KnowledgeBaseServiceImpl service = new KnowledgeBaseServiceImpl(
                knowledgeBaseMapper,
                mock(KnowledgeFileMapper.class),
                auditLogService
        );
        when(knowledgeBaseMapper.selectByIdAndUserId(200L, 42L)).thenReturn(null);

        assertThatThrownBy(() -> service.updateName(42L, 200L, new UpdateKnowledgeBaseDTO("新名称")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");

        verify(knowledgeBaseMapper, never()).updateById(any(KnowledgeBaseEntity.class));
        verify(auditLogService, never()).record(anyLong(), anyString(), anyString(), anyString());
    }

    private static KnowledgeBaseEntity knowledgeBase(Long id, Long userId, String name) {
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setId(id);
        entity.setUserId(userId);
        entity.setName(name);
        entity.setCreatedAt(LocalDateTime.parse("2026-06-30T18:00:00"));
        return entity;
    }
}
