package com.example.aiapp.controller;

import com.example.aiapp.infrastructure.client.AiServiceClient;
import com.example.aiapp.utils.CurrentUserUtils;
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
@RequestMapping("/goals")
public class GoalController {

    private final AiServiceClient aiServiceClient;

    public GoalController(AiServiceClient aiServiceClient) {
        this.aiServiceClient = aiServiceClient;
    }

    @GetMapping
    Map<String, Object> list(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false, defaultValue = "active") String status,
            @RequestParam(defaultValue = "100") int limit
    ) {
        return aiServiceClient.listGoals(currentUserId(), projectId, status, limit);
    }

    @PostMapping
    Map<String, Object> create(@RequestBody Map<String, Object> request) {
        return aiServiceClient.createGoal(currentUserId(), request);
    }

    @GetMapping("/{goalId}")
    Map<String, Object> detail(@PathVariable long goalId) {
        return aiServiceClient.getGoal(currentUserId(), goalId);
    }

    @PatchMapping("/{goalId}")
    Map<String, Object> update(@PathVariable long goalId, @RequestBody Map<String, Object> request) {
        return aiServiceClient.updateGoal(currentUserId(), goalId, request);
    }

    @GetMapping("/{goalId}/milestones")
    Map<String, Object> milestones(@PathVariable long goalId) {
        return aiServiceClient.listMilestones(currentUserId(), goalId);
    }

    @PostMapping("/{goalId}/milestones")
    Map<String, Object> createMilestone(@PathVariable long goalId, @RequestBody Map<String, Object> request) {
        return aiServiceClient.createMilestone(currentUserId(), goalId, request);
    }

    @PostMapping("/{goalId}/tasks/{taskId}/link")
    Map<String, Object> linkTask(
            @PathVariable long goalId,
            @PathVariable String taskId,
            @RequestBody(required = false) Map<String, Object> request
    ) {
        return aiServiceClient.linkTaskToGoal(currentUserId(), goalId, taskId, request == null ? Map.of() : request);
    }

    private String currentUserId() {
        return String.valueOf(CurrentUserUtils.currentUserId());
    }
}
