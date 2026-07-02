package com.example.aiapp.domain.vo;

import java.util.List;
import java.util.Map;

public record FileUploadVO(
        Long fileId,
        Long knowledgeBaseId,
        String fileName,
        String storagePath,
        String parseStatus,
        String ingestTaskId,
        List<Map<String, Object>> candidateMemories
) {
}
