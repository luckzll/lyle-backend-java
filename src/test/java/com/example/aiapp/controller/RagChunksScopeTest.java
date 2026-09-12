package com.example.aiapp.controller;

import com.example.aiapp.domain.entity.KnowledgeBaseEntity;
import com.example.aiapp.infrastructure.client.AiServiceClient;
import com.example.aiapp.mapper.KnowledgeBaseMapper;
import com.example.aiapp.security.CurrentUser;
import com.example.aiapp.service.impl.KnowledgeRetrievalScopeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class RagChunksScopeTest {
    private final AiServiceClient client = mock(AiServiceClient.class);
    private final KnowledgeBaseMapper bases = mock(KnowledgeBaseMapper.class);
    private final KnowledgeRetrievalScopeService scopes = mock(KnowledgeRetrievalScopeService.class);
    private final RagDebugController controller = new RagDebugController(client, bases);

    private void login(String role) {
        controller.setKnowledgeRetrievalScopeService(scopes);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(7L, "user", role), null));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void ownedChunksUseFreshServerManifestAndScopedPost() {
        login("USER");
        when(bases.selectByIdAndUserId(10L, 7L)).thenReturn(new KnowledgeBaseEntity());
        var trusted = Map.<String, Object>of("userId", "7", "documentRevisions", Map.of("10", Map.of("1", "new")));
        when(scopes.build(7L, List.of("10"), false)).thenReturn(trusted);
        controller.ragChunks("10", 500);
        verify(client).getRagChunksScoped(Map.of("userId", "7", "knowledgeBaseIds", List.of("10"),
                "limit", 200, "knowledgeScope", trusted));
        verify(client, never()).getRagChunks(any(), anyInt());
    }

    @Test
    void onlyAdminWithoutAKnowledgeBaseCanUseGlobalLegacyGet() {
        login("ADMIN");
        controller.ragChunks(null, 50);
        verify(client).getRagChunks(null, 50);
        verify(client, never()).getRagChunksScoped(any());
        verifyNoInteractions(scopes);
    }

    @Test
    void adminWithAKnowledgeBaseStillUsesItsDocumentManifest() {
        login("ADMIN");
        var trusted = Map.<String, Object>of("userId", "7", "authorizationRevision", "admin-scope");
        when(scopes.build(7L, List.of("10"), true)).thenReturn(trusted);
        controller.ragChunks("10", 20);
        verify(client).getRagChunksScoped(Map.of("userId", "7", "knowledgeBaseIds", List.of("10"),
                "limit", 20, "knowledgeScope", trusted));
        verify(client, never()).getRagChunks(any(), anyInt());
    }

    @Test
    void ordinaryUserCannotRequestGlobalOrOtherUsersChunks() {
        login("USER");
        assertThatThrownBy(() -> controller.ragChunks(null, 50)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.ragChunks("10", 50)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.ragChunks("", 50)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(client, scopes);
    }

    @Test
    void manifestOwnershipFailureNeverFallsBackToUnscopedGet() {
        login("USER");
        when(bases.selectByIdAndUserId(10L, 7L)).thenReturn(new KnowledgeBaseEntity());
        when(scopes.build(7L, List.of("10"), false)).thenThrow(new IllegalStateException("scope unavailable"));
        assertThatThrownBy(() -> controller.ragChunks("10", 50)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(client);
    }
}
