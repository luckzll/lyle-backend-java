package com.example.aiapp.domain.dto;

public record AiChatAttachmentDTO(
        String id,
        String kind,
        String name,
        String mimeType,
        long size,
        StorageObjectRefDTO storage
) {
    public record StorageObjectRefDTO(
            String storagePath,
            String storageProvider,
            String bucket,
            String objectKey,
            String originalName,
            String contentType,
            long fileSize
    ) {
    }
}
