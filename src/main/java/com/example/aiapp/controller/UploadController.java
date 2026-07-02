package com.example.aiapp.controller;

import com.example.aiapp.domain.vo.ImageUploadVO;
import com.example.aiapp.infrastructure.storage.TosImageStorageService;
import com.example.aiapp.service.AuditLogService;
import com.example.aiapp.utils.CurrentUserUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/uploads")
public class UploadController {

    private final TosImageStorageService tosImageStorageService;
    private final AuditLogService auditLogService;

    public UploadController(TosImageStorageService tosImageStorageService, AuditLogService auditLogService) {
        this.tosImageStorageService = tosImageStorageService;
        this.auditLogService = auditLogService;
    }

    @PostMapping("/images")
    ImageUploadVO uploadImage(@RequestParam("file") MultipartFile file) {
        Long userId = CurrentUserUtils.currentUserId();
        ImageUploadVO result = tosImageStorageService.upload(userId, file);
        auditLogService.record(userId, "upload", "image", result.objectKey());
        return result;
    }
}
