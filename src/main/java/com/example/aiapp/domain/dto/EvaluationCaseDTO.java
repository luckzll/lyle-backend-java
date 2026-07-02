package com.example.aiapp.domain.dto;

import java.util.List;
import java.util.Map;

public record EvaluationCaseDTO(
        String id,
        String question,
        String answer,
        List<Map<String, String>> contexts,
        String expectedAnswer,
        List<String> mustInclude,
        List<String> mustNotInclude,
        String type,
        String rubric
) {
}
