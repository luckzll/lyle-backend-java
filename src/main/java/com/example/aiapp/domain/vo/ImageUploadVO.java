package com.example.aiapp.domain.vo;

public record ImageUploadVO(
        String fileName,
        String url,
        String objectKey,
        String contentType,
        long size
) {
}
