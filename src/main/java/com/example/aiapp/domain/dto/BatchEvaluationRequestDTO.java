package com.example.aiapp.domain.dto;

import java.util.List;

public record BatchEvaluationRequestDTO(
        List<EvaluationCaseDTO> cases,
        String name,
        Long evaluationSetId
) {
}
