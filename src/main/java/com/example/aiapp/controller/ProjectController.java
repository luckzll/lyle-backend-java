package com.example.aiapp.controller;

import com.example.aiapp.infrastructure.client.AiServiceClient;
import com.example.aiapp.utils.CurrentUserUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/projects")
public class ProjectController {

    private final AiServiceClient aiServiceClient;

    public ProjectController(AiServiceClient aiServiceClient) {
        this.aiServiceClient = aiServiceClient;
    }

    @GetMapping
    Map<String, Object> list(
            @RequestParam(required = false, defaultValue = "active") String status,
            @RequestParam(defaultValue = "100") int limit
    ) {
        return aiServiceClient.listProjects(currentUserId(), status, limit);
    }

    @PostMapping
    Map<String, Object> create(@RequestBody Map<String, Object> request) {
        return aiServiceClient.createProject(currentUserId(), request);
    }

    @GetMapping("/{projectId}")
    Map<String, Object> detail(@PathVariable long projectId) {
        return aiServiceClient.getProject(currentUserId(), projectId);
    }

    @PatchMapping("/{projectId}")
    Map<String, Object> update(@PathVariable long projectId, @RequestBody Map<String, Object> request) {
        return aiServiceClient.updateProject(currentUserId(), projectId, request);
    }

    @GetMapping("/{projectId}/overview")
    Map<String, Object> overview(@PathVariable long projectId) {
        return aiServiceClient.getProjectOverview(currentUserId(), projectId);
    }

    @GetMapping("/{projectId}/links")
    Map<String, Object> links(
            @PathVariable long projectId,
            @RequestParam(required = false) String targetType,
            @RequestParam(defaultValue = "100") int limit
    ) {
        return aiServiceClient.listProjectLinks(currentUserId(), projectId, targetType, limit);
    }

    @PostMapping("/{projectId}/links")
    Map<String, Object> createLink(@PathVariable long projectId, @RequestBody Map<String, Object> request) {
        return aiServiceClient.createProjectLink(currentUserId(), projectId, request);
    }

    @DeleteMapping("/{projectId}/links/{linkId}")
    void deleteLink(@PathVariable long projectId, @PathVariable long linkId) {
        aiServiceClient.deleteProjectLink(currentUserId(), projectId, linkId);
    }

    @PostMapping("/{projectId}/decisions")
    Map<String, Object> createDecision(@PathVariable long projectId, @RequestBody Map<String, Object> request) {
        return aiServiceClient.createProjectDecision(currentUserId(), projectId, request);
    }

    @PostMapping("/{projectId}/open-questions")
    Map<String, Object> createOpenQuestion(@PathVariable long projectId, @RequestBody Map<String, Object> request) {
        return aiServiceClient.createProjectOpenQuestion(currentUserId(), projectId, request);
    }

    @PatchMapping("/open-questions/{questionId}")
    Map<String, Object> updateOpenQuestion(@PathVariable long questionId, @RequestBody Map<String, Object> request) {
        return aiServiceClient.updateProjectOpenQuestion(currentUserId(), questionId, request);
    }

    private String currentUserId() {
        return String.valueOf(CurrentUserUtils.currentUserId());
    }
}
