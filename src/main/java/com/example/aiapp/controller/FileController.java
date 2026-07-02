package com.example.aiapp.controller;

import com.example.aiapp.domain.vo.FileUploadVO;
import com.example.aiapp.domain.vo.KnowledgeFileVO;
import com.example.aiapp.domain.vo.PageVO;
import com.example.aiapp.service.FileService;
import com.example.aiapp.utils.CurrentUserUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/knowledge-bases/{knowledgeBaseId}/files")
public class FileController {

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping
    FileUploadVO upload(
            @PathVariable Long knowledgeBaseId,
            @RequestParam("file") MultipartFile file
    ) {
        return fileService.upload(CurrentUserUtils.currentUserId(), knowledgeBaseId, file);
    }

    @GetMapping
    List<KnowledgeFileVO> list(@PathVariable Long knowledgeBaseId) {
        return fileService.listByKnowledgeBaseId(CurrentUserUtils.currentUserId(), knowledgeBaseId);
    }

    @GetMapping("/page")
    PageVO<KnowledgeFileVO> page(
            @PathVariable Long knowledgeBaseId,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size
    ) {
        return fileService.pageByKnowledgeBaseId(CurrentUserUtils.currentUserId(), knowledgeBaseId, page, size);
    }

    @PostMapping("/{fileId}/rebuild")
    FileUploadVO rebuild(@PathVariable Long knowledgeBaseId, @PathVariable Long fileId) {
        return fileService.rebuild(CurrentUserUtils.currentUserId(), knowledgeBaseId, fileId);
    }

    @PostMapping("/rebuild")
    List<FileUploadVO> rebuildKnowledgeBase(@PathVariable Long knowledgeBaseId) {
        return fileService.rebuildKnowledgeBase(CurrentUserUtils.currentUserId(), knowledgeBaseId);
    }

    @DeleteMapping("/{fileId}")
    void delete(@PathVariable Long knowledgeBaseId, @PathVariable Long fileId) {
        fileService.delete(CurrentUserUtils.currentUserId(), knowledgeBaseId, fileId);
    }
}
