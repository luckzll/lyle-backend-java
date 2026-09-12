package com.example.aiapp.domain.dto;

public record AiIngestRequestDTO(
        String fileId,
        String knowledgeBaseId,
        String storagePath,
        String storageProvider,
        String bucket,
        String objectKey,
        String originalName,
        String contentType,
        Long fileSize,
        String visibility,
        String userId,
        String retrievalRevision
) {
    public AiIngestRequestDTO(String fileId, String knowledgeBaseId, String storagePath,
                             String storageProvider, String bucket, String objectKey, String originalName,
                             String contentType, Long fileSize, String visibility, String userId) {
        this(fileId, knowledgeBaseId, storagePath, storageProvider, bucket, objectKey, originalName,
                contentType, fileSize, visibility, userId, "legacy");
    }
}
