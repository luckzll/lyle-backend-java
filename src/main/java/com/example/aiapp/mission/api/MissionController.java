package com.example.aiapp.mission.api;

import com.example.aiapp.mission.application.MissionService;
import com.example.aiapp.mission.infrastructure.events.MissionEventPublisher;
import com.example.aiapp.utils.CurrentUserUtils;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
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

@RestController
@RequestMapping("/missions")
public class MissionController {

    private final MissionService service;
    private final MissionEventPublisher events;

    public MissionController(MissionService service, MissionEventPublisher events) {
        this.service = service;
        this.events = events;
    }

    @PostMapping
    Map<String, Object> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> request
    ) {
        Map<String, Object> value = new java.util.LinkedHashMap<>(request);
        value.put("requestIdempotencyKey", idempotencyKey);
        return service.create(userId(), value, idempotencyKey);
    }

    @GetMapping
    Map<String, Object> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit
    ) {
        return service.list(userId(), status, limit);
    }

    @GetMapping("/digest")
    Map<String, Object> digest(@RequestParam(defaultValue = "20") int limit) {
        return service.digest(userId(), limit);
    }

    @GetMapping("/{id}")
    Map<String, Object> detail(@PathVariable long id) {
        return service.detail(userId(), id);
    }

    @PutMapping("/{id}")
    Map<String, Object> update(
            @PathVariable long id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> request
    ) {
        long userId = userId();
        return service.mutateIdempotently(
                userId,
                "MISSION_UPDATE:" + id,
                idempotencyKey,
                () -> service.update(userId, id, expectedVersion(request), request)
        );
    }

    @PostMapping("/{id}:generate-draft")
    Map<String, Object> generateDraft(
            @PathVariable long id,
            @RequestBody Map<String, Object> request
    ) {
        Map<String, Object> value = new java.util.LinkedHashMap<>(request);
        value.put("sourceMissionId", String.valueOf(id));
        return service.generateDraft(userId(), value);
    }

    @PostMapping("/{id}:shadow")
    Map<String, Object> shadow(
            @PathVariable long id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> request
    ) {
        long userId = userId();
        return service.mutateIdempotently(
                userId,
                "MISSION_SHADOW:" + id,
                idempotencyKey,
                () -> service.shadow(userId, id, expectedVersion(request), request)
        );
    }

    @PostMapping("/{id}:activate")
    Map<String, Object> submitApproval(
            @PathVariable long id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> request
    ) {
        long userId = userId();
        return service.mutateIdempotently(
                userId,
                "MISSION_SUBMIT_APPROVAL:" + id,
                idempotencyKey,
                () -> service.submitForApproval(
                        userId, id, expectedVersion(request),
                        integer(request.get("grantVersion"), 0))
        );
    }

    @PostMapping("/{id}:pause")
    Map<String, Object> pause(
            @PathVariable long id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> request
    ) {
        long userId = userId();
        return service.mutateIdempotently(
                userId,
                "MISSION_PAUSE:" + id,
                idempotencyKey,
                () -> service.pause(userId, id, expectedVersion(request))
        );
    }

    @PostMapping("/{id}:resume")
    Map<String, Object> resume(
            @PathVariable long id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> request
    ) {
        long userId = userId();
        return service.mutateIdempotently(
                userId,
                "MISSION_RESUME:" + id,
                idempotencyKey,
                () -> service.resume(userId, id, expectedVersion(request))
        );
    }

    @PostMapping("/{id}:revoke")
    Map<String, Object> revoke(
            @PathVariable long id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> request
    ) {
        long userId = userId();
        return service.mutateIdempotently(
                userId,
                "MISSION_REVOKE:" + id,
                idempotencyKey,
                () -> service.revoke(userId, id, expectedVersion(request))
        );
    }

    @PostMapping("/{id}:run-now")
    Map<String, Object> runNow(
            @PathVariable long id,
            @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return service.runNow(userId(), id, idempotencyKey);
    }

    @GetMapping("/{id}/runs")
    Map<String, Object> runs(
            @PathVariable long id,
            @RequestParam(defaultValue = "100") int limit
    ) {
        return service.listRuns(userId(), id, limit);
    }

    @GetMapping("/{id}/decisions")
    Map<String, Object> decisions(
            @PathVariable long id,
            @RequestParam(defaultValue = "200") int limit
    ) {
        return service.listDecisions(userId(), id, limit);
    }

    @GetMapping("/{id}/occurrences")
    Map<String, Object> occurrences(
            @PathVariable long id,
            @RequestParam(defaultValue = "100") int limit
    ) {
        return service.listOccurrences(userId(), id, limit);
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events(
            @PathVariable long id,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            @RequestParam(value = "after", required = false) Long after
    ) {
        service.detail(userId(), id);
        return events.subscribe(id, after == null ? parseSequence(lastEventId) : Math.max(0, after));
    }

    @PostMapping("/{id}/grants")
    Map<String, Object> createGrant(
            @PathVariable long id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> request
    ) {
        long userId = userId();
        return service.mutateIdempotently(
                userId,
                "GRANT_CREATE:" + id,
                idempotencyKey,
                () -> service.createGrantVersion(userId, id, expectedVersion(request), request)
        );
    }

    @PostMapping("/{id}/grants/{version}:approve")
    Map<String, Object> approveGrant(
            @PathVariable long id,
            @PathVariable int version,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> request
    ) {
        long userId = userId();
        return service.mutateIdempotently(
                userId,
                "GRANT_APPROVE:" + id + ":" + version,
                idempotencyKey,
                () -> service.approveGrant(userId, id, version, expectedVersion(request))
        );
    }

    @PutMapping("/{id}/grants/{version}")
    Map<String, Object> replaceGrant(
            @PathVariable long id,
            @PathVariable int version,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> request
    ) {
        long userId = userId();
        return service.mutateIdempotently(
                userId,
                "GRANT_REPLACE:" + id + ":" + version,
                idempotencyKey,
                () -> service.replaceGrantVersion(
                        userId, id, version, expectedVersion(request), request)
        );
    }

    @PostMapping("/{id}/grants/{version}:revoke")
    Map<String, Object> revokeGrant(
            @PathVariable long id,
            @PathVariable int version,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> request
    ) {
        long userId = userId();
        return service.mutateIdempotently(
                userId,
                "GRANT_REVOKE:" + id + ":" + version,
                idempotencyKey,
                () -> service.revokeGrant(userId, id, version, expectedVersion(request))
        );
    }

    @GetMapping("/{id}/grants/{version}/diff")
    Map<String, Object> grantDiff(
            @PathVariable long id,
            @PathVariable int version
    ) {
        return service.grantDiff(userId(), id, version);
    }

    @PostMapping("/{id}/triggers")
    Map<String, Object> createTrigger(
            @PathVariable long id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> request
    ) {
        long userId = userId();
        return service.mutateIdempotently(
                userId,
                "TRIGGER_CREATE:" + id,
                idempotencyKey,
                () -> service.createTrigger(userId, id, expectedVersion(request), request)
        );
    }

    @PutMapping("/{id}/triggers/{triggerId}")
    Map<String, Object> updateTrigger(
            @PathVariable long id,
            @PathVariable long triggerId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> request
    ) {
        long userId = userId();
        return service.mutateIdempotently(
                userId,
                "TRIGGER_UPDATE:" + id + ":" + triggerId,
                idempotencyKey,
                () -> service.updateTrigger(
                        userId, id, triggerId, expectedVersion(request), request)
        );
    }

    @DeleteMapping("/{id}/triggers/{triggerId}")
    Map<String, Object> deleteTrigger(
            @PathVariable long id,
            @PathVariable long triggerId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestParam long expectedVersion,
            @RequestParam(defaultValue = "-1") long triggerVersion
    ) {
        long userId = userId();
        return service.mutateIdempotently(
                userId,
                "TRIGGER_DELETE:" + id + ":" + triggerId,
                idempotencyKey,
                () -> service.deleteTrigger(
                        userId, id, triggerId, expectedVersion, triggerVersion)
        );
    }

    private static long userId() {
        return CurrentUserUtils.currentUserId();
    }

    private static long expectedVersion(Map<String, Object> request) {
        long value = longValue(request.get("expectedVersion"));
        if (value < 0 || request.get("expectedVersion") == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "expectedVersion 不能为空。"
            );
        }
        return value;
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null ? -1 : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return -1;
        }
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
