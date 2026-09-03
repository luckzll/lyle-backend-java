package com.example.aiapp.controller;

import com.example.aiapp.infrastructure.client.AiServiceClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 提示词管理接口。
 */
@RestController
@RequestMapping("/prompts")
public class PromptController {

    private final AiServiceClient aiServiceClient;

    public PromptController(AiServiceClient aiServiceClient) {
        this.aiServiceClient = aiServiceClient;
    }

    @GetMapping
    Map<String, Object> promptTemplates(@RequestParam(defaultValue = "50") int limit) {
        return aiServiceClient.getPromptTemplates(limit);
    }

    @PostMapping
    Map<String, Object> savePromptTemplate(@RequestBody Map<String, Object> request) {
        return aiServiceClient.savePromptTemplate(request);
    }

    @PostMapping("/{promptId}/activate")
    Map<String, Object> activatePromptTemplate(@PathVariable long promptId) {
        return aiServiceClient.activatePromptTemplate(promptId);
    }
}
