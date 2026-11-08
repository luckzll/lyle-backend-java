package com.example.aiapp.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.aiapp.domain.entity.KnowledgeBaseEntity;
import com.example.aiapp.domain.entity.KnowledgeFileEntity;
import com.example.aiapp.domain.vo.PageVO;
import com.example.aiapp.domain.vo.FileUploadVO;
import com.example.aiapp.domain.dto.UpdateKnowledgeBaseDTO;
import com.example.aiapp.mapper.KnowledgeBaseMapper;
import com.example.aiapp.mapper.KnowledgeFileMapper;
import com.example.aiapp.service.FileService;
import com.example.aiapp.service.KnowledgeBaseService;
import com.example.aiapp.service.AuditLogService;
import com.example.aiapp.utils.CurrentUserUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.Locale;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.CONFLICT;

/** Administrative metadata access. Storage paths and private file contents are never returned. */
@RestController
@RequestMapping("/admin/knowledge-bases")
public class AdminKnowledgeController {
    private final KnowledgeBaseMapper bases;
    private final KnowledgeFileMapper files;
    private final FileService fileService;
    private final KnowledgeBaseService baseService;
    private final AuditLogService audit;
    public AdminKnowledgeController(KnowledgeBaseMapper bases, KnowledgeFileMapper files,
            FileService fileService, KnowledgeBaseService baseService, AuditLogService audit) {
        this.bases=bases; this.files=files; this.fileService=fileService; this.baseService=baseService; this.audit=audit;
    }
    public record BaseView(String id, String userId, String name, LocalDateTime createdAt) {}
    public record FileView(String id, String knowledgeBaseId, String fileName, Long fileSize,
                           String parseStatus, LocalDateTime createdAt) {}
    public record RetryResult(String fileId, String ingestTaskId, String parseStatus) {}
    @GetMapping
    public PageVO<BaseView> list(@RequestParam(defaultValue="1") @Min(1) long page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) long size,
            @RequestParam(required=false) Long userId,
            @RequestParam(defaultValue="") @Size(max=100) String keyword) {
        var query = new QueryWrapper<KnowledgeBaseEntity>();
        if (userId != null) query.eq("user_id",userId);
        if (!keyword.isBlank()) query.like("name",keyword.trim());
        query.orderByDesc("created_at").orderByDesc("id");
        var result=bases.selectPage(Page.of(page,size),query);
        return new PageVO<>(result.getRecords().stream().map(this::view).toList(),result.getTotal(),page,size);
    }
    @GetMapping("/{id}")
    public BaseView detail(@PathVariable Long id) { return view(base(id)); }
    @GetMapping("/{id}/files/{fileId}")
    public FileView file(@PathVariable Long id, @PathVariable Long fileId) {
        base(id);
        var file = files.selectByIdAndKnowledgeBaseId(fileId, id);
        if (file == null) throw new ResponseStatusException(NOT_FOUND, "文件不存在");
        return new FileView(String.valueOf(file.getId()), String.valueOf(id), file.getFileName(),
                file.getFileSize(), normalize(file.getParseStatus()), file.getCreatedAt());
    }
    @GetMapping("/{id}/files")
    public PageVO<FileView> files(@PathVariable Long id,
            @RequestParam(defaultValue="1") @Min(1) long page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) long size,
            @RequestParam(defaultValue="") @Size(max=100) String keyword,
            @RequestParam(defaultValue="") @Pattern(regexp="(?i)(|FAILED|PROCESSING|QUEUED|PENDING|COMPLETED|SUCCESS|READY)") String status) {
        base(id);
        var query=new QueryWrapper<KnowledgeFileEntity>().eq("knowledge_base_id",id);
        if (!keyword.isBlank()) query.like("file_name",keyword.trim());
        if (!status.isBlank()) {
            if ("COMPLETED".equals(normalize(status))) {
                query.apply("UPPER(parse_status) IN ({0}, {1}, {2})", "COMPLETED", "SUCCESS", "READY");
            } else {
                query.apply("UPPER(parse_status) = {0}", status.toUpperCase(Locale.ROOT));
            }
        }
        query.orderByDesc("created_at").orderByDesc("id");
        var result=files.selectPage(Page.of(page,size),query);
        return new PageVO<>(result.getRecords().stream().map(f->new FileView(String.valueOf(f.getId()),
                String.valueOf(id),f.getFileName(),f.getFileSize(),normalize(f.getParseStatus()),f.getCreatedAt())).toList(),result.getTotal(),page,size);
    }
    @PatchMapping("/{id}")
    public BaseView rename(@PathVariable Long id, @Valid @RequestBody UpdateKnowledgeBaseDTO request) {
        var owner=base(id).getUserId();
        baseService.updateName(owner,id,request);
        record("ADMIN_KNOWLEDGE_RENAME",id);
        return view(base(id));
    }
    @PostMapping("/{id}/files")
    public RetryResult upload(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
        var result=fileService.upload(base(id).getUserId(),id,file);
        record("ADMIN_KNOWLEDGE_UPLOAD",id);
        return result(result);
    }
    @PostMapping("/{id}/files/{fileId}/rebuild")
    @Transactional
    public RetryResult retry(@PathVariable Long id, @PathVariable Long fileId) {
        var owner=base(id).getUserId();
        if(files.lockAdminRetry(fileId,id)==null) throw new ResponseStatusException(NOT_FOUND,"文件不存在");
        var file=files.selectByIdAndKnowledgeBaseId(fileId,id);
        if(file==null) throw new ResponseStatusException(NOT_FOUND,"文件不存在");
        if(!"FAILED".equalsIgnoreCase(file.getParseStatus()))
            throw new ResponseStatusException(CONFLICT,"仅允许重试失败文件，请刷新状态。");
        var result=fileService.rebuild(owner,id,fileId);
        record("ADMIN_KNOWLEDGE_RETRY",fileId);
        return result(result);
    }
    private KnowledgeBaseEntity base(Long id) {
        var entity=bases.selectById(id);
        if(entity==null) throw new ResponseStatusException(NOT_FOUND,"知识库不存在");
        return entity;
    }
    private BaseView view(KnowledgeBaseEntity entity) {
        return new BaseView(String.valueOf(entity.getId()),String.valueOf(entity.getUserId()),entity.getName(),entity.getCreatedAt());
    }
    private RetryResult result(FileUploadVO result) {
        return new RetryResult(String.valueOf(result.fileId()),result.ingestTaskId(),normalize(result.parseStatus()));
    }
    private String normalize(String status) {
        if (status == null) return "UNKNOWN";
        var normalized = status.toUpperCase(Locale.ROOT);
        return java.util.Set.of("COMPLETED", "SUCCESS", "READY").contains(normalized) ? "COMPLETED" : normalized;
    }
    private void record(String action,Long id) { audit.record(CurrentUserUtils.currentUserId(),action,"KNOWLEDGE",String.valueOf(id)); }
}
