package com.example.aiapp.service.impl;

import com.example.aiapp.domain.entity.KnowledgeBaseEntity;
import com.example.aiapp.domain.entity.KnowledgeFileEntity;
import com.example.aiapp.mapper.KnowledgeBaseMapper;
import com.example.aiapp.mapper.KnowledgeFileMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeRetrievalScopeServiceTest {
    private final KnowledgeBaseMapper bases = mock(KnowledgeBaseMapper.class);
    private final KnowledgeFileMapper files = mock(KnowledgeFileMapper.class);
    private final KnowledgeRetrievalScopeService service = new KnowledgeRetrievalScopeService(bases, files);

    private void owned() {
        KnowledgeBaseEntity base = new KnowledgeBaseEntity();
        base.setId(10L);
        base.setUserId(7L);
        when(bases.selectByIdAndUserId(10L, 7L)).thenReturn(base);
    }

    private KnowledgeFileEntity file(long id, String status) {
        KnowledgeFileEntity file = new KnowledgeFileEntity();
        file.setId(id);
        file.setKnowledgeBaseId(10L);
        file.setParseStatus(status);
        file.setRetrievalRevision("v1");
        file.setStoragePath("private/storage/" + id);
        return file;
    }

    @Test
    void onlyCompletedFilesAreRetrievableAndManifestIsStableUnderRowOrder() {
        owned();
        var ready = file(1, "completed");
        var pending = file(2, "queued");
        var failed = file(3, "failed");
        when(files.selectByKnowledgeBaseId(10L)).thenReturn(List.of(ready, pending, failed));
        var first = service.build(7L, List.of("10"), false);
        assertThat(first.get("allowedDocumentIds")).isEqualTo(Map.of("10", List.of("1")));
        assertThat(first.toString()).doesNotContain("private/storage");
        when(files.selectByKnowledgeBaseId(10L)).thenReturn(List.of(failed, ready, pending));
        assertThat(service.build(7L, List.of("10", "10"), false)).isEqualTo(first);
    }

    @Test
    void deletionAndRebuildInvalidateManifestEvenAfterReturningToCompleted() {
        owned();
        var ready = file(1, "completed");
        when(files.selectByKnowledgeBaseId(10L)).thenReturn(List.of(ready));
        var before = service.build(7L, List.of("10"), false);
        ready.setRetrievalRevision("v2");
        var rebuilt = service.build(7L, List.of("10"), false);
        assertThat(rebuilt.get("knowledgeBaseRevisions")).isNotEqualTo(before.get("knowledgeBaseRevisions"));
        when(files.selectByKnowledgeBaseId(10L)).thenReturn(List.of());
        var deleted = service.build(7L, List.of("10"), false);
        assertThat(deleted.get("allowedDocumentIds")).isEqualTo(Map.of("10", List.of()));
        assertThat(deleted.get("knowledgeBaseRevisions")).isNotEqualTo(rebuilt.get("knowledgeBaseRevisions"));
    }

    @Test
    void checksOwnershipBeforeReadingFileManifestOnEveryRequest() {
        owned();
        when(files.selectByKnowledgeBaseId(10L)).thenReturn(List.of(file(1, "completed")));
        service.build(7L, List.of("10"), false);
        when(bases.selectByIdAndUserId(10L, 7L)).thenReturn(null);
        assertThatThrownBy(() -> service.build(7L, List.of("10"), false))
                .isInstanceOf(ResponseStatusException.class);
        verify(files, times(1)).selectByKnowledgeBaseId(10L);
        assertThatThrownBy(() -> service.build(8L, List.of("10"), false))
                .isInstanceOf(ResponseStatusException.class);
    }
}
