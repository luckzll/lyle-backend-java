package com.example.aiapp.controller;

import com.example.aiapp.infrastructure.client.AiServiceClient;
import com.example.aiapp.utils.CurrentUserUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/briefings")
public class BriefingController {

    private final AiServiceClient aiServiceClient;

    public BriefingController(AiServiceClient aiServiceClient) {
        this.aiServiceClient = aiServiceClient;
    }

    @GetMapping("/today")
    Map<String, Object> today(@RequestParam(required = false) Long projectId) {
        return aiServiceClient.getTodayBriefing(currentUserId(), projectId);
    }

    @PostMapping("/daily/generate")
    Map<String, Object> generateDaily(@RequestParam(required = false) Long projectId) {
        return aiServiceClient.generateDailyBriefing(currentUserId(), projectId);
    }

    @PostMapping("/evening/generate")
    Map<String, Object> generateEvening(@RequestParam(required = false) Long projectId) {
        return aiServiceClient.generateEveningBriefing(currentUserId(), projectId);
    }

    @PostMapping("/weekly/generate")
    Map<String, Object> generateWeekly(@RequestParam(required = false) Long projectId) {
        return aiServiceClient.generateWeeklyBriefing(currentUserId(), projectId);
    }

    private String currentUserId() {
        return String.valueOf(CurrentUserUtils.currentUserId());
    }
}
