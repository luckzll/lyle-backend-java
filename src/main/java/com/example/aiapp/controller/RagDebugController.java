package com.example.aiapp.controller;

import com.example.aiapp.infrastructure.client.AiServiceClient;
import com.example.aiapp.mapper.KnowledgeBaseMapper;
import com.example.aiapp.security.CurrentUser;
import com.example.aiapp.utils.CurrentUserUtils;
import com.example.aiapp.service.impl.KnowledgeRetrievalScopeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/rag")
public class RagDebugController {

    private final AiServiceClient aiServiceClient;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private KnowledgeRetrievalScopeService knowledgeRetrievalScopeService;

    @Autowired
    void setKnowledgeRetrievalScopeService(KnowledgeRetrievalScopeService service) {
        this.knowledgeRetrievalScopeService = service;
    }

    public RagDebugController(AiServiceClient aiServiceClient, KnowledgeBaseMapper knowledgeBaseMapper) {
        this.aiServiceClient = aiServiceClient;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
    }

    @PostMapping("/debug")
    Map<String, Object> debugRag(@RequestBody Map<String, Object> request) {
        ensureKnowledgeBasesAccessible(extractKnowledgeBaseIds(request));
        CurrentUser user = CurrentUserUtils.currentUser();
        Map<String, Object> trustedRequest = new LinkedHashMap<>(request);
        trustedRequest.put("userId", String.valueOf(user.id()));
        trustedRequest.put("knowledgeScope", knowledgeRetrievalScopeService == null ? Map.of()
                : knowledgeRetrievalScopeService.build(user.id(), extractKnowledgeBaseIds(request),
                "ADMIN".equals(user.role())));
        return aiServiceClient.debugRag(trustedRequest);
    }

    @GetMapping("/chunks")
    Map<String, Object> ragChunks(
            @RequestParam(required = false) String knowledgeBaseId,
            @RequestParam(defaultValue = "50") int limit
    ) {
        List<String> ids = knowledgeBaseId == null ? List.of() : List.of(knowledgeBaseId);
        ensureKnowledgeBasesAccessible(ids);
        CurrentUser user = CurrentUserUtils.currentUser();
        if ("ADMIN".equals(user.role()) && ids.isEmpty()) {
            return aiServiceClient.getRagChunks(null, limit);
        }
        Map<String, Object> trustedRequest = new LinkedHashMap<>();
        trustedRequest.put("userId", String.valueOf(user.id()));
        trustedRequest.put("knowledgeBaseIds", ids);
        trustedRequest.put("limit", Math.max(1, Math.min(limit, 200)));
        trustedRequest.put("knowledgeScope", knowledgeRetrievalScopeService.build(user.id(), ids,
                "ADMIN".equals(user.role())));
        return aiServiceClient.getRagChunksScoped(trustedRequest);
    }

    private List<String> extractKnowledgeBaseIds(Map<String, Object> request) {
        Object raw = request.get("knowledgeBaseIds");
        if (!(raw instanceof List<?> ids)) {
            return List.of();
        }
        return ids.stream().map(String::valueOf).toList();
    }

    /**
     * 非管理员必须指定知识库并且只允许访问自己的知识库，防止越权读取他人 chunk。
     */
    private void ensureKnowledgeBasesAccessible(List<String> knowledgeBaseIds) {
        CurrentUser user = CurrentUserUtils.currentUser();
        if ("ADMIN".equals(user.role())) {
            return;
        }
        if (knowledgeBaseIds.isEmpty()) {
            throw new ResponseStatusException(FORBIDDEN, "请指定属于自己的知识库");
        }
        for (String rawId : knowledgeBaseIds) {
            long knowledgeBaseId;
            try {
                knowledgeBaseId = Long.parseLong(rawId);
            } catch (NumberFormatException exception) {
                throw new ResponseStatusException(NOT_FOUND, "知识库不存在");
            }
            if (knowledgeBaseMapper.selectByIdAndUserId(knowledgeBaseId, user.id()) == null) {
                throw new ResponseStatusException(NOT_FOUND, "知识库不存在");
            }
        }
    }
}
