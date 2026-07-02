package com.example.aiapp.service;

import com.example.aiapp.domain.vo.FileUploadVO;
import com.example.aiapp.domain.vo.KnowledgeFileVO;
import com.example.aiapp.domain.vo.PageVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface FileService {

    FileUploadVO upload(Long userId, Long knowledgeBaseId, MultipartFile file);

    List<KnowledgeFileVO> listByKnowledgeBaseId(Long userId, Long knowledgeBaseId);

    PageVO<KnowledgeFileVO> pageByKnowledgeBaseId(Long userId, Long knowledgeBaseId, long page, long size);

    FileUploadVO rebuild(Long userId, Long knowledgeBaseId, Long fileId);

    List<FileUploadVO> rebuildKnowledgeBase(Long userId, Long knowledgeBaseId);

    void delete(Long userId, Long knowledgeBaseId, Long fileId);
}
