package com.example.aiapp.service;

import com.example.aiapp.domain.dto.AiChatAttachmentDTO;
import com.example.aiapp.domain.entity.ChatAttachmentEntity;
import com.example.aiapp.domain.vo.ChatAttachmentVO;
import com.example.aiapp.infrastructure.storage.FileStorageService;
import com.example.aiapp.infrastructure.storage.StoredObject;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.aiapp.mapper.ChatAttachmentMapper;

@Service
public class ChatAttachmentService {

    public static final int MAX_COUNT = 8;
    public static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    public static final long MAX_TOTAL_BYTES = 20L * 1024 * 1024;
    private static final String BUCKET = "chat-attachments";
    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp", "image/gif");
    private static final Set<String> FILE_TYPES = Set.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "text/plain", "text/markdown", "text/csv", "application/json", "application/x-ndjson"
    );
    private static final Set<String> FILE_EXTENSIONS = Set.of("pdf", "docx", "txt", "md", "csv", "json", "log", "xlsx");

    private final ChatAttachmentMapper mapper;
    private final FileStorageService storage;

    public ChatAttachmentService(ChatAttachmentMapper mapper, FileStorageService storage) {
        this.mapper = mapper;
        this.storage = storage;
    }

    @Transactional
    public ChatAttachmentVO upload(Long userId, MultipartFile file, String requestedKind) {
        byte[] bytes = validateAndRead(file);
        String name = safeFileName(file.getOriginalFilename());
        String contentType = normalizedContentType(file.getContentType(), name);
        String kind = IMAGE_TYPES.contains(contentType) ? "image" : "file";
        if (StringUtils.hasText(requestedKind) && !kind.equals(requestedKind)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "附件类型与文件内容不匹配");
        }
        validateSignature(bytes, contentType, name);

        String id = UUID.randomUUID().toString();
        String objectName = userId + "/" + id + extension(name);
        StoredObject stored;
        try {
            stored = storage.upload(BUCKET, objectName, new ByteArrayInputStream(bytes), bytes.length, contentType);
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "附件上传失败", exception);
        }

        ChatAttachmentEntity entity = new ChatAttachmentEntity();
        entity.setId(id);
        entity.setUserId(userId);
        entity.setKind(kind);
        entity.setStatus("READY");
        entity.setOriginalName(name);
        entity.setContentType(contentType);
        entity.setFileSize((long) bytes.length);
        entity.setStorageProvider(stored.provider());
        entity.setStoragePath(stored.storagePath());
        entity.setBucket(stored.bucket());
        entity.setObjectKey(stored.objectKey());
        if ("image".equals(kind)) {
            readDimensions(bytes, entity);
        }
        entity.setCreatedAt(LocalDateTime.now());
        entity.setExpiresAt(LocalDateTime.now().plusHours(24));
        mapper.insert(entity);
        return toVO(entity);
    }

    public List<ChatAttachmentVO> listForMessage(Long messageId) {
        if (messageId == null) return List.of();
        return mapper.selectList(new LambdaQueryWrapper<ChatAttachmentEntity>()
                        .eq(ChatAttachmentEntity::getMessageId, messageId)
                        .eq(ChatAttachmentEntity::getStatus, "ATTACHED")
                        .orderByAsc(ChatAttachmentEntity::getPosition))
                .stream().map(this::toVO).toList();
    }

    public List<AiChatAttachmentDTO> requireReady(Long userId, List<String> rawIds) {
        List<ChatAttachmentEntity> entities = readyEntities(userId, rawIds);
        return entities.stream().map(this::toAiDTO).toList();
    }

    public List<AiChatAttachmentDTO> requireAttachedForRetry(
            Long userId,
            String conversationId,
            Long messageId,
            List<String> rawIds
    ) {
        Long persistedConversationId;
        try {
            persistedConversationId = Long.valueOf(conversationId);
        } catch (NumberFormatException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只能重试已保存会话中的附件");
        }
        List<ChatAttachmentEntity> found = mapper.selectList(new LambdaQueryWrapper<ChatAttachmentEntity>()
                .eq(ChatAttachmentEntity::getUserId, userId)
                .eq(ChatAttachmentEntity::getConversationId, persistedConversationId)
                .eq(ChatAttachmentEntity::getMessageId, messageId)
                .eq(ChatAttachmentEntity::getStatus, "ATTACHED")
                .orderByAsc(ChatAttachmentEntity::getPosition));
        List<String> ids = rawIds == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(rawIds));
        if (ids.size() > MAX_COUNT || ids.size() != (rawIds == null ? 0 : rawIds.size())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "重试附件无效");
        }
        if (!ids.isEmpty()) {
            List<String> originalIds = found.stream().map(ChatAttachmentEntity::getId).toList();
            if (!ids.equals(originalIds)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "重试必须保留原消息的全部附件和顺序");
            }
        }
        long total = found.stream().mapToLong(ChatAttachmentEntity::getFileSize).sum();
        if (total > MAX_TOTAL_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单轮附件总量不能超过 20MB");
        }
        return found.stream().map(this::toAiDTO).toList();
    }

    @Transactional
    public void bind(Long userId, String conversationId, Long messageId, List<String> rawIds) {
        List<ChatAttachmentEntity> entities = readyEntities(userId, rawIds);
        Long persistedConversationId;
        try {
            persistedConversationId = Long.valueOf(conversationId);
        } catch (NumberFormatException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "附件只能绑定到已保存的会话");
        }
        LocalDateTime now = LocalDateTime.now();
        for (int index = 0; index < entities.size(); index++) {
            ChatAttachmentEntity entity = entities.get(index);
            entity.setConversationId(persistedConversationId);
            entity.setMessageId(messageId);
            entity.setPosition(index);
            entity.setStatus("ATTACHED");
            entity.setAttachedAt(now);
            entity.setExpiresAt(null);
            mapper.updateById(entity);
        }
    }

    @Transactional
    public void removeDraft(Long userId, String attachmentId) {
        ChatAttachmentEntity entity = owned(userId, attachmentId);
        if ("ATTACHED".equals(entity.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已发送的附件不能从草稿接口删除");
        }
        entity.setStatus("DELETED");
        entity.setExpiresAt(LocalDateTime.now());
        mapper.updateById(entity);
    }

    public AttachmentContent content(Long userId, String attachmentId) {
        ChatAttachmentEntity entity = owned(userId, attachmentId);
        if (!Set.of("READY", "ATTACHED").contains(entity.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "附件不存在");
        }
        try {
            return new AttachmentContent(entity, storage.download(entity.getBucket(), entity.getObjectKey()));
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "附件内容不可用", exception);
        }
    }

    @Scheduled(fixedDelayString = "${app.chat-attachment.cleanup-millis:3600000}")
    @Transactional
    public void cleanupExpiredDrafts() {
        List<ChatAttachmentEntity> expired = mapper.selectList(new LambdaQueryWrapper<ChatAttachmentEntity>()
                .eq(ChatAttachmentEntity::getStatus, "READY")
                .isNotNull(ChatAttachmentEntity::getExpiresAt)
                .lt(ChatAttachmentEntity::getExpiresAt, LocalDateTime.now())
                .last("LIMIT 100"));
        for (ChatAttachmentEntity entity : expired) {
            try {
                storage.delete(entity.getBucket(), entity.getObjectKey());
                entity.setStatus("EXPIRED");
                mapper.updateById(entity);
            } catch (RuntimeException ignored) {
                // Leave the row READY so a later sweep can retry without losing the storage reference.
            }
        }
    }

    private List<ChatAttachmentEntity> readyEntities(Long userId, List<String> rawIds) {
        List<String> ids = rawIds == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(rawIds));
        if (ids.size() > MAX_COUNT || ids.size() != (rawIds == null ? 0 : rawIds.size())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单轮最多 8 个附件，且不能重复");
        }
        if (ids.isEmpty()) return List.of();
        List<ChatAttachmentEntity> found = mapper.selectList(new LambdaQueryWrapper<ChatAttachmentEntity>()
                .eq(ChatAttachmentEntity::getUserId, userId)
                .in(ChatAttachmentEntity::getId, ids));
        var byId = found.stream().collect(java.util.stream.Collectors.toMap(ChatAttachmentEntity::getId, item -> item));
        List<ChatAttachmentEntity> ordered = new ArrayList<>();
        long total = 0;
        for (String id : ids) {
            ChatAttachmentEntity entity = byId.get(id);
            if (entity == null || !"READY".equals(entity.getStatus())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "附件未就绪或不属于当前用户");
            }
            total += entity.getFileSize();
            ordered.add(entity);
        }
        if (total > MAX_TOTAL_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单轮附件总量不能超过 20MB");
        }
        return ordered;
    }

    private ChatAttachmentEntity owned(Long userId, String attachmentId) {
        ChatAttachmentEntity entity = mapper.selectOne(new LambdaQueryWrapper<ChatAttachmentEntity>()
                .eq(ChatAttachmentEntity::getId, attachmentId)
                .eq(ChatAttachmentEntity::getUserId, userId));
        if (entity == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "附件不存在");
        return entity;
    }

    private byte[] validateAndRead(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择附件");
        if (file.getSize() > MAX_FILE_BYTES) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单个附件不能超过 5MB");
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "附件读取失败", exception);
        }
    }

    private String normalizedContentType(String raw, String name) {
        String value = raw == null ? "" : raw.toLowerCase(Locale.ROOT).split(";", 2)[0];
        String ext = extensionValue(name);
        if (IMAGE_TYPES.contains(value)) return value;
        if (FILE_TYPES.contains(value) && FILE_EXTENSIONS.contains(ext)) return value;
        return switch (ext) {
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "md" -> "text/markdown";
            case "csv" -> "text/csv";
            case "json" -> "application/json";
            case "txt", "log" -> "text/plain";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持该附件格式");
        };
    }

    private void validateSignature(byte[] bytes, String contentType, String name) {
        boolean valid = switch (contentType) {
            case "image/jpeg" -> starts(bytes, 0xff, 0xd8, 0xff);
            case "image/png" -> starts(bytes, 0x89, 0x50, 0x4e, 0x47);
            case "image/gif" -> starts(bytes, 0x47, 0x49, 0x46, 0x38);
            case "image/webp" -> starts(bytes, 0x52, 0x49, 0x46, 0x46) && bytes.length > 11
                    && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
            case "application/pdf" -> starts(bytes, 0x25, 0x50, 0x44, 0x46);
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> starts(bytes, 0x50, 0x4b);
            default -> !extensionValue(name).isBlank();
        };
        if (!valid) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "附件内容与声明格式不匹配");
    }

    private boolean starts(byte[] bytes, int... signature) {
        if (bytes.length < signature.length) return false;
        for (int index = 0; index < signature.length; index++) {
            if ((bytes[index] & 0xff) != signature[index]) return false;
        }
        return true;
    }

    private void readDimensions(byte[] bytes, ChatAttachmentEntity entity) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image != null) {
                entity.setWidth(image.getWidth());
                entity.setHeight(image.getHeight());
            }
        } catch (IOException ignored) {
            // Dimensions are optional; signature validation already protected the upload path.
        }
    }

    private String safeFileName(String raw) {
        String value = raw == null ? "attachment" : raw.replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1).replace("\r", "").replace("\n", "").trim();
        if (value.isBlank()) value = "attachment";
        return value.length() > 255 ? value.substring(value.length() - 255) : value;
    }

    private String extension(String name) {
        String ext = extensionValue(name);
        return ext.isBlank() ? "" : "." + ext;
    }

    private String extensionValue(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public ChatAttachmentVO toVO(ChatAttachmentEntity entity) {
        String base = "/chat/attachments/" + entity.getId() + "/content";
        return new ChatAttachmentVO(
                entity.getId(), entity.getKind(), entity.getOriginalName(), entity.getContentType(),
                entity.getFileSize(), entity.getStatus(),
                "image".equals(entity.getKind()) ? base + "?disposition=inline" : null,
                base + "?disposition=attachment", entity.getWidth(), entity.getHeight()
        );
    }

    private AiChatAttachmentDTO toAiDTO(ChatAttachmentEntity entity) {
        return new AiChatAttachmentDTO(
                entity.getId(), entity.getKind(), entity.getOriginalName(), entity.getContentType(), entity.getFileSize(),
                new AiChatAttachmentDTO.StorageObjectRefDTO(
                        entity.getStoragePath(), entity.getStorageProvider(), entity.getBucket(), entity.getObjectKey(),
                        entity.getOriginalName(), entity.getContentType(), entity.getFileSize()
                )
        );
    }

    public record AttachmentContent(ChatAttachmentEntity entity, InputStream stream) {
    }
}
