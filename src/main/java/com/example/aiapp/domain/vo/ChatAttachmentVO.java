package com.example.aiapp.domain.vo;

public record ChatAttachmentVO(
        String id,
        String kind,
        String name,
        String mimeType,
        long size,
        String status,
        String previewUrl,
        String downloadUrl,
        Integer width,
        Integer height,
        Integer position,
        String sourceType,
        String folderGroupId,
        String folderRoot,
        String relativePath
) {
}
