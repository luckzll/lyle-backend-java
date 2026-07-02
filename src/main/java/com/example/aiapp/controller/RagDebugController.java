package com.example.aiapp.controller;

import com.example.aiapp.infrastructure.client.AiServiceClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/rag")
public class RagDebugController {

    private final AiServiceClient aiServiceClient;

    public RagDebugController(AiServiceClient aiServiceClient) {
        this.aiServiceClient = aiServiceClient;
    }

    @PostMapping("/debug")
    Map<String, Object> debugRag(@RequestBody Map<String, Object> request) {
        return aiServiceClient.debugRag(request);
    }

    @GetMapping("/chunks")
    Map<String, Object> ragChunks(
            @RequestParam(required = false) String knowledgeBaseId,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return aiServiceClient.getRagChunks(knowledgeBaseId, limit);
    }
}
