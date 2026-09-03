package com.example.aiapp.agenttask.controller;

import com.example.aiapp.agenttask.application.AgentTaskService;
import com.example.aiapp.agenttask.infrastructure.events.AgentEventPublisher;
import com.example.aiapp.utils.CurrentUserUtils;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 智能体任务创建、规划、执行与状态管理接口。
 */
@RestController
@RequestMapping("/agent-tasks")
public class AgentTaskController {

    private final AgentTaskService service;
    private final AgentEventPublisher events;

    public AgentTaskController(AgentTaskService service, AgentEventPublisher events) {
        this.service = service;
        this.events = events;
    }

    @PostMapping
    Map<String, Object> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> request
    ) {
        return service.create(currentUserId(), idempotencyKey, request);
    }

    @GetMapping
    Map<String, Object> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return service.list(currentUserId(), status, limit);
    }

    @GetMapping("/{id}")
    Map<String, Object> detail(@PathVariable long id) {
        return service.detail(currentUserId(), id);
    }

    @PostMapping("/{id}/plan:generate")
    Map<String, Object> generatePlan(
            @PathVariable long id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> request
    ) {
        return service.generatePlan(currentUserId(), id, expectedVersion(request), idempotencyKey);
    }

    @PutMapping("/{id}/plans/{version}")
    Map<String, Object> updatePlan(
            @PathVariable long id,
            @PathVariable int version,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> request
    ) {
        return service.updatePlan(currentUserId(), id, version, expectedVersion(request), request, idempotencyKey);
    }

    @PostMapping("/{id}/plans/{version}:approve")
    Map<String, Object> approvePlan(
            @PathVariable long id,
            @PathVariable int version,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> request
    ) {
        return service.approvePlan(currentUserId(), id, version, expectedVersion(request), idempotencyKey);
    }

    @PostMapping("/{id}:start")
    Map<String, Object> start(
            @PathVariable long id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> request
    ) {
        return service.start(currentUserId(), id, expectedVersion(request), idempotencyKey);
    }

    @PostMapping("/{id}:pause")
    Map<String, Object> pause(
            @PathVariable long id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> request
    ) {
        return service.pause(currentUserId(), id, expectedVersion(request), idempotencyKey);
    }

    @PostMapping("/{id}:resume")
    Map<String, Object> resume(
            @PathVariable long id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> request
    ) {
        return service.resume(currentUserId(), id, expectedVersion(request), idempotencyKey);
    }

    @PostMapping("/{id}:cancel")
    Map<String, Object> cancel(
            @PathVariable long id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> request
    ) {
        return service.cancel(currentUserId(), id, expectedVersion(request), idempotencyKey);
    }

    @PostMapping("/{id}:provide-input")
    Map<String, Object> provideInput(
            @PathVariable long id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> request
    ) {
        return service.provideInput(currentUserId(), id, expectedVersion(request), request, idempotencyKey);
    }

    @GetMapping("/{id}/artifacts")
    Map<String, List<Map<String, Object>>> artifacts(@PathVariable long id) {
        return Map.of("items", service.artifacts(currentUserId(), id));
    }

    @GetMapping("/{id}/audit")
    Map<String, List<Map<String, Object>>> audit(
            @PathVariable long id,
            @RequestParam(defaultValue = "100") int limit
    ) {
        return Map.of("items", service.audit(currentUserId(), id, limit));
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events(
            @PathVariable long id,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            @RequestParam(value = "after", required = false) Long after
    ) {
        service.requireAccess(currentUserId(), id);
        return events.subscribe(id, after == null ? parseSequence(lastEventId) : Math.max(0, after));
    }

    private static long currentUserId() {
        return CurrentUserUtils.currentUserId();
    }

    private static long expectedVersion(Map<String, Object> request) {
        Object value = request.get("expectedVersion");
        if (value instanceof Number number && number.longValue() >= 0) return number.longValue();
        if (value instanceof String text) {
            try {
                long parsed = Long.parseLong(text);
                if (parsed >= 0) return parsed;
            } catch (NumberFormatException ignored) {
                // Fall through to the same user-facing validation error.
            }
        }
        throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "expectedVersion 不能为空。"
        );
    }

    private static long parseSequence(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            return Math.max(0, Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
