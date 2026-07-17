package com.example.aiapp.mission.api;

import com.example.aiapp.mission.application.MissionService;
import com.example.aiapp.utils.CurrentUserUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/exceptions")
public class MissionExceptionController {

    private final MissionService service;

    public MissionExceptionController(MissionService service) {
        this.service = service;
    }

    @GetMapping
    Map<String, Object> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit
    ) {
        return service.listExceptions(userId(), status, limit);
    }

    @GetMapping("/{id}")
    Map<String, Object> detail(@PathVariable long id) {
        return service.getException(userId(), id);
    }

    @PostMapping("/{id}:acknowledge")
    Map<String, Object> acknowledge(
            @PathVariable long id,
            @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        long userId = userId();
        return service.mutateIdempotently(
                userId,
                "EXCEPTION_ACK:" + id,
                idempotencyKey,
                () -> service.acknowledgeException(userId, id)
        );
    }

    @PostMapping("/{id}:resolve")
    Map<String, Object> resolve(
            @PathVariable long id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> request
    ) {
        long userId = userId();
        return service.mutateIdempotently(
                userId,
                "EXCEPTION_RESOLVE:" + id,
                idempotencyKey,
                () -> service.resolveException(
                        userId, id, String.valueOf(request.getOrDefault("action", "")))
        );
    }

    @PostMapping("/{id}:dismiss")
    Map<String, Object> dismiss(
            @PathVariable long id,
            @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        long userId = userId();
        return service.mutateIdempotently(
                userId,
                "EXCEPTION_DISMISS:" + id,
                idempotencyKey,
                () -> service.dismissException(userId, id)
        );
    }

    private static long userId() {
        return CurrentUserUtils.currentUserId();
    }
}
