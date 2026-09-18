package com.example.aiapp.domain.dto;

public record AiChatAttachmentDTO(
        String id,
        String kind,
        String name,
        String mimeType,
        long size,
        String sourceType,
        String folderGroupId,
        String folderRoot,
        String relativePath,
        StorageObjectRefDTO storage,
        String sourceMessageId
) {
    public AiChatAttachmentDTO(String id, String kind, String name, String mimeType, long size, StorageObjectRefDTO storage) {
        this(id, kind, name, mimeType, size, null, null, null, null, storage, null);
    }
    public AiChatAttachmentDTO(String id, String kind, String name, String mimeType, long size,
                               String sourceType, String folderGroupId, String folderRoot, String relativePath,
                               StorageObjectRefDTO storage) {
        this(id, kind, name, mimeType, size, sourceType, folderGroupId, folderRoot, relativePath, storage, null);
    }
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
