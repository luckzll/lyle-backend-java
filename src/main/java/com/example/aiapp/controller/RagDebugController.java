package com.example.aiapp.controller;

import com.example.aiapp.infrastructure.client.AiServiceClient;
import com.example.aiapp.mapper.KnowledgeBaseMapper;
import com.example.aiapp.security.CurrentUser;
import com.example.aiapp.utils.CurrentUserUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/rag")
public class RagDebugController {

    private final AiServiceClient aiServiceClient;
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    public RagDebugController(AiServiceClient aiServiceClient, KnowledgeBaseMapper knowledgeBaseMapper) {
        this.aiServiceClient = aiServiceClient;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
    }

    @PostMapping("/debug")
    Map<String, Object> debugRag(@RequestBody Map<String, Object> request) {
        ensureKnowledgeBasesAccessible(extractKnowledgeBaseIds(request));
        return aiServiceClient.debugRag(request);
    }

    @GetMapping("/chunks")
    Map<String, Object> ragChunks(
            @RequestParam(required = false) String knowledgeBaseId,
            @RequestParam(defaultValue = "50") int limit
    ) {
        ensureKnowledgeBasesAccessible(knowledgeBaseId == null ? List.of() : List.of(knowledgeBaseId));
        return aiServiceClient.getRagChunks(knowledgeBaseId, limit);
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
