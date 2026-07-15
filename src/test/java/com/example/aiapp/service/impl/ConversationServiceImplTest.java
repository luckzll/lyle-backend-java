package com.example.aiapp.service.impl;

import com.example.aiapp.domain.dto.UpdateConversationDTO;
import com.example.aiapp.domain.entity.ConversationEntity;
import com.example.aiapp.mapper.ChatMessageMapper;
import com.example.aiapp.mapper.ConversationMapper;
import com.example.aiapp.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationServiceImplTest {

    @Test
    void ownerCanUpdateTitleAndTheValueIsTrimmed() {
        ConversationMapper conversationMapper = mock(ConversationMapper.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        ConversationServiceImpl service = new ConversationServiceImpl(
                conversationMapper,
                mock(ChatMessageMapper.class),
                auditLogService
        );
        ConversationEntity entity = conversation(100L, 42L, "旧标题");
        when(conversationMapper.selectByIdAndUserId(100L, 42L)).thenReturn(entity);

        var result = service.updateTitle(42L, 100L, new UpdateConversationDTO("  新标题  "));

        assertThat(result.title()).isEqualTo("新标题");
        assertThat(entity.getUpdatedAt()).isNotNull();
        verify(conversationMapper).updateById(entity);
        verify(auditLogService).record(42L, "update", "conversation", "100");
    }

    @Test
    void anotherUsersConversationIsNotUpdated() {
        ConversationMapper conversationMapper = mock(ConversationMapper.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        ConversationServiceImpl service = new ConversationServiceImpl(
                conversationMapper,
                mock(ChatMessageMapper.class),
                auditLogService
        );
        when(conversationMapper.selectByIdAndUserId(100L, 42L)).thenReturn(null);

        assertThatThrownBy(() -> service.updateTitle(42L, 100L, new UpdateConversationDTO("新标题")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");

        verify(conversationMapper, never()).updateById(
                org.mockito.ArgumentMatchers.any(ConversationEntity.class)
        );
        verify(auditLogService, never()).record(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()
        );
    }

    private static ConversationEntity conversation(Long id, Long userId, String title) {
        ConversationEntity entity = new ConversationEntity();
        entity.setId(id);
        entity.setUserId(userId);
        entity.setTitle(title);
        entity.setCreatedAt(LocalDateTime.parse("2026-07-11T16:00:00"));
        entity.setUpdatedAt(LocalDateTime.parse("2026-07-11T16:00:00"));
        return entity;
    }
}
