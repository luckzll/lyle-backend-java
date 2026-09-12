package com.example.aiapp.service.impl;

import com.example.aiapp.domain.dto.AiIngestRequestDTO;
import com.example.aiapp.domain.dto.AiIngestResponseDTO;
import com.example.aiapp.domain.entity.KnowledgeBaseEntity;
import com.example.aiapp.domain.entity.KnowledgeFileEntity;
import com.example.aiapp.infrastructure.client.AiServiceClient;
import com.example.aiapp.infrastructure.storage.FileStorageService;
import com.example.aiapp.mapper.KnowledgeBaseMapper;
import com.example.aiapp.mapper.KnowledgeFileMapper;
import com.example.aiapp.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FileRebuildRevisionTest {
    @Test
    void lateCompletionUpdatesOnlyItsRevisionAndNeverRestoresOldEntity() {
        var files = mock(KnowledgeFileMapper.class);
        var bases = mock(KnowledgeBaseMapper.class);
        var client = mock(AiServiceClient.class);
        var service = new FileServiceImpl(files, bases, mock(FileStorageService.class), client, mock(AuditLogService.class));
        KnowledgeFileEntity attempt = new KnowledgeFileEntity();
        attempt.setId(1L); attempt.setKnowledgeBaseId(10L); attempt.setParseStatus("completed");
        attempt.setRetrievalRevision("original"); attempt.setStoragePath("safe-test-path");
        KnowledgeFileEntity newer = new KnowledgeFileEntity();
        newer.setId(1L); newer.setKnowledgeBaseId(10L); newer.setParseStatus("queued");
        newer.setRetrievalRevision("newer-rebuild");
        when(bases.selectByIdAndUserId(10L, 7L)).thenReturn(new KnowledgeBaseEntity());
        when(files.selectByIdAndKnowledgeBaseId(1L, 10L)).thenReturn(attempt, newer);
        when(client.ingest(any())).thenReturn(new AiIngestResponseDTO("task", "completed", "ok", List.of()));
        when(files.updateParseStatusIfRevision(eq(1L), eq(10L), anyString(), eq("completed"))).thenReturn(0);
        var response = service.rebuild(7L, 10L, 1L);
        ArgumentCaptor<AiIngestRequestDTO> captured = ArgumentCaptor.forClass(AiIngestRequestDTO.class);
        verify(client).ingest(captured.capture());
        String revision = captured.getValue().retrievalRevision();
        assertThat(revision).isNotBlank().isNotEqualTo("original");
        verify(files).updateParseStatusIfRevision(1L, 10L, revision, "completed");
        // The only full-row update queues the new attempt, before the remote call.
        verify(files, times(1)).updateById(attempt);
        assertThat(response.parseStatus()).isEqualTo("queued");
        assertThat(newer.getRetrievalRevision()).isEqualTo("newer-rebuild");
    }
}
