package com.example.aiapp.mission.api;

import com.example.aiapp.mission.application.MissionService;
import com.example.aiapp.utils.CurrentUserUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 智能体操作补偿记录查询接口。
 */
@RestController
@RequestMapping("/compensations")
public class CompensationLookupController {

    private final MissionService service;

    public CompensationLookupController(MissionService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    Map<String, Object> detail(@PathVariable long id) {
        return service.compensationById(CurrentUserUtils.currentUserId(), id);
    }
}
