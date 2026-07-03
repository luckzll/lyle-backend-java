package com.example.aiapp.infrastructure.storage;

public record StoredObject(
        String provider,
        String bucket,
        String objectKey,
        String storagePath,
        String contentType,
        long size,
        String visibility
) {
}
