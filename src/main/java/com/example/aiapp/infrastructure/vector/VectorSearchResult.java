package com.example.aiapp.infrastructure.vector;

public record VectorSearchResult(
        String id,
        float score,
        String payload
) {
}
