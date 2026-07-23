package com.example.aiapp.controller;

import com.example.aiapp.domain.vo.ChatAttachmentVO;
import com.example.aiapp.service.ChatAttachmentService;
import com.example.aiapp.utils.CurrentUserUtils;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

@RestController
@RequestMapping("/chat/attachments")
public class ChatAttachmentController {

    private final ChatAttachmentService service;

    public ChatAttachmentController(ChatAttachmentService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ChatAttachmentVO upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "sourceType", required = false) String sourceType,
            @RequestParam(value = "folderGroupId", required = false) String folderGroupId,
            @RequestParam(value = "folderRoot", required = false) String folderRoot,
            @RequestParam(value = "relativePath", required = false) String relativePath
    ) {
        return service.upload(CurrentUserUtils.currentUserId(), file, kind, sourceType, folderGroupId, folderRoot, relativePath);
    }

    @GetMapping("/{attachmentId}/content")
    ResponseEntity<InputStreamResource> content(
            @PathVariable String attachmentId,
            @RequestParam(value = "disposition", defaultValue = "attachment") String disposition
    ) {
        var content = service.content(CurrentUserUtils.currentUserId(), attachmentId);
        var entity = content.entity();
        boolean inline = "inline".equals(disposition.toLowerCase(Locale.ROOT)) && "image".equals(entity.getKind());
        ContentDisposition header = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(entity.getOriginalName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(entity.getContentType()))
                .contentLength(entity.getFileSize())
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, header.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(content.stream()));
    }

    @DeleteMapping("/{attachmentId}")
    ResponseEntity<Void> delete(@PathVariable String attachmentId) {
        service.removeDraft(CurrentUserUtils.currentUserId(), attachmentId);
        return ResponseEntity.noContent().build();
    }
}
