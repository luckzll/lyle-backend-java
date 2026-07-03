package com.example.aiapp.domain.vo;

import java.time.LocalDateTime;

public record KnowledgeFileVO(
        Long id,
        Long knowledgeBaseId,
        String fileName,
        String storagePath,
        String storageProvider,
        String bucket,
        String objectKey,
        String originalName,
        String contentType,
        Long fileSize,
        String visibility,
        String parseStatus,
        LocalDateTime createdAt
) {
}
