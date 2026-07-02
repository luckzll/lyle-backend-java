package com.example.aiapp.domain.vo;

import java.time.LocalDateTime;

public record KnowledgeFileVO(
        Long id,
        Long knowledgeBaseId,
        String fileName,
        String storagePath,
        String parseStatus,
        LocalDateTime createdAt
) {
}
