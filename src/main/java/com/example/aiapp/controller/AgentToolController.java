package com.example.aiapp.controller;

import com.example.aiapp.domain.dto.ToolStatusUpdateDTO;
import com.example.aiapp.domain.dto.TodoDueAtUpdateDTO;
import com.example.aiapp.infrastructure.client.AiServiceClient;
import com.example.aiapp.utils.CurrentUserUtils;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/agent-tools")
public class AgentToolController {

    private final AiServiceClient aiServiceClient;

    public AgentToolController(AiServiceClient aiServiceClient) {
        this.aiServiceClient = aiServiceClient;
    }

    @GetMapping("/todos")
    Map<String, Object> todos(@RequestParam(defaultValue = "50") int limit) {
        return aiServiceClient.getTodos(currentUserId(), limit);
    }

    @PostMapping("/todos")
    Map<String, Object> createTodo(@RequestBody Map<String, Object> request) {
        return aiServiceClient.createTodo(currentUserId(), request);
    }

    @PatchMapping("/todos/{todoId}/status")
    Map<String, Object> updateTodoStatus(
            @PathVariable long todoId,
            @Valid @RequestBody ToolStatusUpdateDTO request
    ) {
        return aiServiceClient.updateTodoStatus(currentUserId(), todoId, Map.of("status", request.status()));
    }

    @PatchMapping("/todos/{todoId}/due-at")
    Map<String, Object> updateTodoDueAt(
            @PathVariable long todoId,
            @RequestBody TodoDueAtUpdateDTO request
    ) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("dueAt", request.dueAt());
        return aiServiceClient.updateTodoDueAt(currentUserId(), todoId, payload);
    }

    @DeleteMapping("/todos/{todoId}")
    void deleteTodo(@PathVariable long todoId) {
        aiServiceClient.deleteTodo(currentUserId(), todoId);
    }

    @GetMapping("/calendar")
    Map<String, Object> calendar(@RequestParam(defaultValue = "50") int limit) {
        return aiServiceClient.getCalendarEvents(currentUserId(), limit);
    }

    @PatchMapping("/calendar/{eventId}/status")
    Map<String, Object> updateCalendarStatus(
            @PathVariable long eventId,
            @Valid @RequestBody ToolStatusUpdateDTO request
    ) {
        return aiServiceClient.updateCalendarStatus(currentUserId(), eventId, Map.of("status", request.status()));
    }

    @DeleteMapping("/calendar/{eventId}")
    void deleteCalendarEvent(@PathVariable long eventId) {
        aiServiceClient.deleteCalendarEvent(currentUserId(), eventId);
    }

    @GetMapping("/reminders")
    Map<String, Object> reminders(@RequestParam(defaultValue = "50") int limit) {
        return aiServiceClient.getReminders(currentUserId(), limit);
    }

    @PatchMapping("/reminders/{notificationId}/read")
    Map<String, Object> markReminderRead(@PathVariable long notificationId) {
        return aiServiceClient.markReminderRead(currentUserId(), notificationId);
    }

    private String currentUserId() {
        return String.valueOf(CurrentUserUtils.currentUserId());
    }
}
