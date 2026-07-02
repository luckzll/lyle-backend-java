package com.example.aiapp.controller;

import com.example.aiapp.infrastructure.client.AiServiceClient;
import com.example.aiapp.utils.CurrentUserUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/memory")
public class MemoryController {

    private final AiServiceClient aiServiceClient;

    public MemoryController(AiServiceClient aiServiceClient) {
        this.aiServiceClient = aiServiceClient;
    }

    @GetMapping("/current")
    Map<String, Object> current(@RequestParam(required = false) String conversationId) {
        return aiServiceClient.getMemory(String.valueOf(CurrentUserUtils.currentUserId()), conversationId);
    }

    @PutMapping("/structured")
    Map<String, Object> saveStructured(@RequestBody Map<String, Object> request) {
        return aiServiceClient.upsertStructuredMemory(String.valueOf(CurrentUserUtils.currentUserId()), request);
    }

    @DeleteMapping("/structured/{key}")
    void deleteStructured(@PathVariable String key) {
        aiServiceClient.deleteStructuredMemory(String.valueOf(CurrentUserUtils.currentUserId()), key);
    }

    @DeleteMapping("/structured/{key}/purge")
    void purgeStructured(@PathVariable String key) {
        aiServiceClient.purgeStructuredMemory(String.valueOf(CurrentUserUtils.currentUserId()), key);
    }

    @PutMapping("/structured/{key}/status")
    Map<String, Object> changeStructuredStatus(@PathVariable String key, @RequestBody Map<String, String> request) {
        return aiServiceClient.changeStructuredMemoryStatus(
                String.valueOf(CurrentUserUtils.currentUserId()),
                key,
                request
        );
    }

    @GetMapping("/structured/{key}/history")
    Map<String, Object> structuredHistory(
            @PathVariable String key,
            @RequestParam(defaultValue = "30") int limit
    ) {
        return aiServiceClient.getStructuredMemoryHistory(
                String.valueOf(CurrentUserUtils.currentUserId()),
                key,
                limit
        );
    }

    @GetMapping("/structured/merge-suggestions")
    Map<String, Object> mergeSuggestions() {
        return aiServiceClient.getStructuredMemoryMergeSuggestions(String.valueOf(CurrentUserUtils.currentUserId()));
    }
}
