package com.example.aiapp.controller;

import com.example.aiapp.domain.dto.BatchEvaluationRequestDTO;
import com.example.aiapp.infrastructure.client.AiServiceClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AI 评测接口。
 */
@RestController
@RequestMapping("/evaluation")
public class EvaluationController {

    private final AiServiceClient aiServiceClient;

    public EvaluationController(AiServiceClient aiServiceClient) {
        this.aiServiceClient = aiServiceClient;
    }

    @PostMapping("/batch")
    Map<String, Object> evaluateBatch(@RequestBody BatchEvaluationRequestDTO request) {
        return aiServiceClient.evaluateBatch(request);
    }

    @GetMapping("/runs")
    Map<String, Object> evaluationRuns(@RequestParam(defaultValue = "20") int limit) {
        return aiServiceClient.getEvaluationRuns(limit);
    }

    @GetMapping("/runs/{runId}")
    Map<String, Object> evaluationRun(@PathVariable long runId) {
        return aiServiceClient.getEvaluationRun(runId);
    }

    @GetMapping("/runs/{runId}/report")
    Map<String, Object> evaluationRunReport(@PathVariable long runId) {
        return aiServiceClient.getEvaluationRunReport(runId);
    }

    @GetMapping("/trends")
    Map<String, Object> evaluationTrends(@RequestParam(defaultValue = "50") int limit) {
        return aiServiceClient.getEvaluationTrends(limit);
    }

    @GetMapping("/sets")
    Map<String, Object> evaluationSets(@RequestParam(defaultValue = "20") int limit) {
        return aiServiceClient.getEvaluationSets(limit);
    }

    @PostMapping("/sets")
    Map<String, Object> saveEvaluationSet(@RequestBody Map<String, Object> request) {
        return aiServiceClient.saveEvaluationSet(request);
    }

    @PutMapping("/sets/{setId}")
    Map<String, Object> updateEvaluationSet(@PathVariable long setId, @RequestBody Map<String, Object> request) {
        return aiServiceClient.updateEvaluationSet(setId, request);
    }

    @DeleteMapping("/sets/{setId}")
    void deleteEvaluationSet(@PathVariable long setId) {
        aiServiceClient.deleteEvaluationSet(setId);
    }

    @GetMapping("/sets/{setId}")
    Map<String, Object> evaluationSet(@PathVariable long setId) {
        return aiServiceClient.getEvaluationSet(setId);
    }

    @PostMapping("/sets/{setId}/run")
    Map<String, Object> runEvaluationSet(
            @PathVariable long setId,
            @RequestParam(defaultValue = "false") boolean badcasesOnly
    ) {
        return aiServiceClient.runEvaluationSet(setId, badcasesOnly);
    }
}
