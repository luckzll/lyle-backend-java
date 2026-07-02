package com.example.aiapp.domain.vo;

public record AdminOverviewVO(
        long users,
        long conversations,
        long knowledgeBases,
        long knowledgeFiles,
        long auditLogs
) {
}
