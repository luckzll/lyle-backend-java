package com.example.aiapp.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.aiapp.domain.dto.AiIngestRequestDTO;
import com.example.aiapp.domain.dto.AiIngestResponseDTO;
import com.example.aiapp.domain.entity.KnowledgeFileEntity;
import com.example.aiapp.domain.vo.FileUploadVO;
import com.example.aiapp.domain.vo.KnowledgeFileVO;
import com.example.aiapp.domain.vo.PageVO;
import com.example.aiapp.infrastructure.client.AiServiceClient;
import com.example.aiapp.infrastructure.storage.FileStorageService;
import com.example.aiapp.infrastructure.storage.StoredObject;
import com.example.aiapp.mapper.KnowledgeBaseMapper;
import com.example.aiapp.mapper.KnowledgeFileMapper;
import com.example.aiapp.service.AuditLogService;
import com.example.aiapp.service.FileService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 知识库文件服务实现。
 */
@Service
public class FileServiceImpl implements FileService {

    private static final String KNOWLEDGE_BUCKET = "knowledge-files";
    private static final String STATUS_QUEUED = "queued";
    private static final String STATUS_FAILED = "failed";
    private static final Set<String> SUPPORTED_KNOWLEDGE_EXTENSIONS = Set.of(
            ".txt", ".md", ".csv", ".json", ".log",
            ".pdf", ".docx", ".xlsx", ".xlsm", ".xls", ".pptx"
    );

    private final KnowledgeFileMapper knowledgeFileMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final FileStorageService fileStorageService;
    private final AiServiceClient aiServiceClient;
    private final AuditLogService auditLogService;

    public FileServiceImpl(
            KnowledgeFileMapper knowledgeFileMapper,
            KnowledgeBaseMapper knowledgeBaseMapper,
            FileStorageService fileStorageService,
            AiServiceClient aiServiceClient,
            AuditLogService auditLogService
    ) {
        this.knowledgeFileMapper = knowledgeFileMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.fileStorageService = fileStorageService;
        this.aiServiceClient = aiServiceClient;
        this.auditLogService = auditLogService;
    }

    @Override
    public FileUploadVO upload(Long userId, Long knowledgeBaseId, MultipartFile file) {
        ensureKnowledgeBaseOwnedByUser(userId, knowledgeBaseId);
        String originalFilename = file.getOriginalFilename() == null ? "upload.bin" : file.getOriginalFilename();
        if (!isSupportedKnowledgeFile(originalFilename)) {
            throw new ResponseStatusException(
                    BAD_REQUEST,
                    "暂不支持该文件格式，请上传 PDF、DOCX、XLS/XLSX、PPTX 或文本文件"
            );
        }
        String objectName = knowledgeBaseId + "/" + UUID.randomUUID() + "-" + originalFilename;

        try {
            StoredObject storedObject = fileStorageService.upload(
                    KNOWLEDGE_BUCKET,
                    objectName,
                    file.getInputStream(),
                    file.getSize(),
                    file.getContentType()
            );

            KnowledgeFileEntity entity = new KnowledgeFileEntity();
            entity.setKnowledgeBaseId(knowledgeBaseId);
            entity.setFileName(originalFilename);
            entity.setStoragePath(storedObject.storagePath());
            entity.setStorageProvider(storedObject.provider());
            entity.setBucket(storedObject.bucket());
            entity.setObjectKey(storedObject.objectKey());
            entity.setOriginalName(originalFilename);
            entity.setContentType(storedObject.contentType());
            entity.setFileSize(storedObject.size());
            entity.setVisibility(storedObject.visibility());
            entity.setParseStatus(STATUS_QUEUED);
            entity.setCreatedAt(LocalDateTime.now());
            knowledgeFileMapper.insert(entity);
            auditLogService.record(userId, "upload", "knowledge_file", String.valueOf(entity.getId()));

            return triggerIngest(userId, entity);
        } catch (IOException exception) {
            throw new IllegalStateException("File upload failed.", exception);
        }
    }

    @Override
    public List<KnowledgeFileVO> listByKnowledgeBaseId(Long userId, Long knowledgeBaseId) {
        ensureKnowledgeBaseOwnedByUser(userId, knowledgeBaseId);
        return knowledgeFileMapper.selectByKnowledgeBaseId(knowledgeBaseId)
                .stream()
                .map(this::toVO)
                .toList();
    }

    @Override
    public PageVO<KnowledgeFileVO> pageByKnowledgeBaseId(Long userId, Long knowledgeBaseId, long page, long size) {
        ensureKnowledgeBaseOwnedByUser(userId, knowledgeBaseId);
        IPage<KnowledgeFileEntity> result = knowledgeFileMapper.selectPageByKnowledgeBaseId(
                Page.of(page, size),
                knowledgeBaseId
        );
        return new PageVO<>(
                result.getRecords().stream().map(this::toVO).toList(),
                result.getTotal(),
                result.getCurrent(),
                result.getSize()
        );
    }

    @Override
    public FileUploadVO rebuild(Long userId, Long knowledgeBaseId, Long fileId) {
        ensureKnowledgeBaseOwnedByUser(userId, knowledgeBaseId);
        KnowledgeFileEntity entity = knowledgeFileMapper.selectByIdAndKnowledgeBaseId(fileId, knowledgeBaseId);
        if (entity == null) {
            throw new ResponseStatusException(NOT_FOUND, "文件不存在");
        }
        entity.setParseStatus(STATUS_QUEUED);
        knowledgeFileMapper.updateById(entity);
        auditLogService.record(userId, "rebuild", "knowledge_file", String.valueOf(fileId));
        return triggerIngest(userId, entity);
    }

    @Override
    public List<FileUploadVO> rebuildKnowledgeBase(Long userId, Long knowledgeBaseId) {
        ensureKnowledgeBaseOwnedByUser(userId, knowledgeBaseId);
        List<KnowledgeFileEntity> entities = knowledgeFileMapper.selectByKnowledgeBaseId(knowledgeBaseId);
        return entities.stream()
                .map(entity -> {
                    entity.setParseStatus(STATUS_QUEUED);
                    knowledgeFileMapper.updateById(entity);
                    auditLogService.record(userId, "rebuild", "knowledge_file", String.valueOf(entity.getId()));
                    return triggerIngest(userId, entity);
                })
                .toList();
    }

    @Override
    public void delete(Long userId, Long knowledgeBaseId, Long fileId) {
        ensureKnowledgeBaseOwnedByUser(userId, knowledgeBaseId);
        KnowledgeFileEntity entity = knowledgeFileMapper.selectByIdAndKnowledgeBaseId(fileId, knowledgeBaseId);
        if (entity == null) {
            throw new ResponseStatusException(NOT_FOUND, "文件不存在");
        }
        knowledgeFileMapper.deleteById(fileId);
        auditLogService.record(userId, "delete", "knowledge_file", String.valueOf(fileId));
    }

    private KnowledgeFileVO toVO(KnowledgeFileEntity entity) {
        return new KnowledgeFileVO(
                entity.getId(),
                entity.getKnowledgeBaseId(),
                entity.getFileName(),
                entity.getStoragePath(),
                entity.getStorageProvider(),
                entity.getBucket(),
                entity.getObjectKey(),
                entity.getOriginalName(),
                entity.getContentType(),
                entity.getFileSize(),
                entity.getVisibility(),
                entity.getParseStatus(),
                entity.getCreatedAt()
        );
    }

    private FileUploadVO triggerIngest(Long userId, KnowledgeFileEntity entity) {
        AiIngestResponseDTO ingestResponse;
        try {
            ingestResponse = aiServiceClient.ingest(new AiIngestRequestDTO(
                    String.valueOf(entity.getId()),
                    String.valueOf(entity.getKnowledgeBaseId()),
                    entity.getStoragePath(),
                    entity.getStorageProvider(),
                    entity.getBucket(),
                    entity.getObjectKey(),
                    entity.getOriginalName(),
                    entity.getContentType(),
                    entity.getFileSize(),
                    entity.getVisibility(),
                    String.valueOf(userId)
            ));
            entity.setParseStatus(ingestResponse == null ? STATUS_QUEUED : ingestResponse.status());
            knowledgeFileMapper.updateById(entity);
        } catch (RuntimeException exception) {
            entity.setParseStatus(STATUS_FAILED);
            knowledgeFileMapper.updateById(entity);
            throw exception;
        }
        return new FileUploadVO(
                entity.getId(),
                entity.getKnowledgeBaseId(),
                entity.getFileName(),
                entity.getStoragePath(),
                entity.getStorageProvider(),
                entity.getBucket(),
                entity.getObjectKey(),
                entity.getOriginalName(),
                entity.getContentType(),
                entity.getFileSize(),
                entity.getVisibility(),
                entity.getParseStatus(),
                ingestResponse == null ? null : ingestResponse.taskId(),
                ingestResponse == null || ingestResponse.candidateMemories() == null
                        ? List.of()
                        : ingestResponse.candidateMemories()
        );
    }

    private void ensureKnowledgeBaseOwnedByUser(Long userId, Long knowledgeBaseId) {
        if (knowledgeBaseMapper.selectByIdAndUserId(knowledgeBaseId, userId) == null) {
            throw new ResponseStatusException(NOT_FOUND, "知识库不存在");
        }
    }

    static boolean isSupportedKnowledgeFile(String fileName) {
        String normalized = fileName == null ? "" : fileName.trim().toLowerCase(Locale.ROOT);
        int dotIndex = normalized.lastIndexOf('.');
        return dotIndex >= 0 && SUPPORTED_KNOWLEDGE_EXTENSIONS.contains(normalized.substring(dotIndex));
    }
}
