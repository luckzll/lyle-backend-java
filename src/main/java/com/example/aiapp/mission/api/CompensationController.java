package com.example.aiapp.mission.api;

import com.example.aiapp.mission.application.MissionService;
import com.example.aiapp.utils.CurrentUserUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 智能体操作补偿执行接口。
 */
@RestController
@RequestMapping("/agent-actions")
public class CompensationController {

    private final MissionService service;

    public CompensationController(MissionService service) {
        this.service = service;
    }

    @GetMapping("/{stepRunId}/compensation")
    Map<String, Object> compensation(@PathVariable long stepRunId) {
        return service.compensation(CurrentUserUtils.currentUserId(), stepRunId);
    }

    @PostMapping("/{stepRunId}:compensate")
    Map<String, Object> compensate(
            @PathVariable long stepRunId,
            @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        long userId = CurrentUserUtils.currentUserId();
        return service.mutateIdempotently(
                userId,
                "COMPENSATE:" + stepRunId,
                idempotencyKey,
                () -> service.compensate(userId, stepRunId, idempotencyKey)
        );
    }
}
