package com.example.aiapp.controller;

import com.example.aiapp.domain.dto.AiIngestTaskResponseDTO;
import com.example.aiapp.infrastructure.client.AiServiceClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ingest/tasks")
public class IngestTaskController {

    private final AiServiceClient aiServiceClient;

    public IngestTaskController(AiServiceClient aiServiceClient) {
        this.aiServiceClient = aiServiceClient;
    }

    @GetMapping("/{taskId}")
    AiIngestTaskResponseDTO getTask(@PathVariable String taskId) {
        return aiServiceClient.getIngestTask(taskId);
    }
}
