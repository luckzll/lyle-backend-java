package com.example.aiapp.controller;

import com.example.aiapp.domain.entity.KnowledgeBaseEntity;
import com.example.aiapp.infrastructure.client.AiServiceClient;
import com.example.aiapp.mapper.KnowledgeBaseMapper;
import com.example.aiapp.security.CurrentUser;
import com.example.aiapp.service.impl.KnowledgeRetrievalScopeService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;

class RagDebugScopeTest {
    @Test
    void replacesBrowserSuppliedIdentityAndManifestWithServerOwnedValues() {
        var client = mock(AiServiceClient.class);
        var bases = mock(KnowledgeBaseMapper.class);
        var scopes = mock(KnowledgeRetrievalScopeService.class);
        var controller = new RagDebugController(client, bases);
        controller.setKnowledgeRetrievalScopeService(scopes);
        when(bases.selectByIdAndUserId(10L, 7L)).thenReturn(new KnowledgeBaseEntity());
        var trusted = Map.<String, Object>of("userId", "7", "authorizationRevision", "server-revision");
        when(scopes.build(7L, List.of("10"), false)).thenReturn(trusted);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(7L, "user", "USER"), null));
        try {
            controller.debugRag(Map.of("question", "q", "knowledgeBaseIds", List.of("10"),
                    "userId", "someone-else", "knowledgeScope", Map.of("allowedDocumentIds", List.of("private"))));
            verify(client).debugRag(Map.of("question", "q", "knowledgeBaseIds", List.of("10"),
                    "userId", "7", "knowledgeScope", trusted));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
