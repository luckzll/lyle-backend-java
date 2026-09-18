package com.example.aiapp.service.impl;

import com.example.aiapp.domain.dto.AiChatAttachmentDTO;
import com.example.aiapp.domain.entity.ChatAttachmentEntity;
import com.example.aiapp.domain.vo.ChatAttachmentVO;
import com.example.aiapp.infrastructure.storage.FileStorageService;
import com.example.aiapp.infrastructure.storage.StoredObject;
import com.example.aiapp.service.ChatAttachmentPathPolicy;
import com.example.aiapp.service.ChatAttachmentService;
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
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.example.aiapp.mapper.ChatAttachmentMapper;

/**
 * 对话附件服务实现。
 */
@Service
public class ChatAttachmentServiceImpl implements ChatAttachmentService {

    public static final int MAX_COUNT = 200;
    public static final int MAX_REGULAR_COUNT = 8;
    public static final int MAX_IMAGE_COUNT = 8;
    public static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    public static final long MAX_TOTAL_BYTES = 20L * 1024 * 1024;
    private static final String BUCKET = "chat-attachments";
    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp", "image/gif");
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "txt", "md", "csv", "json", "log", "ts", "tsx", "js", "jsx", "mjs", "cjs", "vue", "svelte",
            "py", "java", "kt", "kts", "go", "rs", "c", "h", "cpp", "hpp", "cs", "php", "rb", "swift",
            "sh", "bash", "zsh", "ps1", "sql", "yaml", "yml", "toml", "ini", "cfg", "xml", "html", "htm",
            "css", "scss", "sass", "less", "graphql", "gql", "proto", "properties", "gradle", "scala", "r", "dart", "ipynb"
    );
    private static final Set<String> TEXT_NAMES = Set.of(
            "Dockerfile", "Makefile", "Containerfile", "Jenkinsfile", "Procfile", "README", "LICENSE",
            ".gitignore", ".dockerignore", ".editorconfig"
    );

    private final ChatAttachmentMapper mapper;
    private final FileStorageService storage;

    public ChatAttachmentServiceImpl(ChatAttachmentMapper mapper, FileStorageService storage) {
        this.mapper = mapper;
        this.storage = storage;
    }

    @Override
    @Transactional
    public ChatAttachmentVO upload(Long userId, MultipartFile file, String requestedKind) {
        return upload(userId, file, requestedKind, null, null, null, null);
    }

    @Override
    @Transactional
    public ChatAttachmentVO upload(
            Long userId,
            MultipartFile file,
            String requestedKind,
            String sourceType,
            String folderGroupId,
            String folderRoot,
            String relativePath
    ) {
        if (file == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择附件");
        String name = safeFileName(file.getOriginalFilename());
        byte[] bytes = validateAndRead(file, isTextFile(name));
        var folder = ChatAttachmentPathPolicy.normalize(sourceType, folderGroupId, folderRoot, relativePath, name);
        String contentType = contentTypeFor(name);
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
        entity.setSourceType(folder.sourceType());
        entity.setFolderGroupId(folder.folderGroupId());
        entity.setFolderRoot(folder.folderRoot());
        entity.setRelativePath(folder.relativePath());
        if ("image".equals(kind)) {
            readDimensions(bytes, entity);
        }
        entity.setCreatedAt(LocalDateTime.now());
        entity.setExpiresAt(LocalDateTime.now().plusHours(24));
        mapper.insert(entity);
        return toVO(entity);
    }

    @Override
    public List<ChatAttachmentVO> listForMessage(Long messageId) {
        if (messageId == null) return List.of();
        return mapper.selectAttachedByMessageId(messageId)
                .stream().map(this::toVO).toList();
    }

    @Override
    public List<AiChatAttachmentDTO> requireReady(Long userId, List<String> rawIds) {
        List<ChatAttachmentEntity> entities = readyEntities(userId, rawIds);
        return entities.stream().map(this::toAiDTO).toList();
    }

    @Override
    public List<AiChatAttachmentDTO> requireRecentForConversation(Long userId, String conversationId) {
        Long id;
        try {
            id = Long.valueOf(conversationId);
        } catch (NumberFormatException exception) {
            return List.of();
        }
        return id <= 0 ? List.of() : recentAttachments(userId, id, null).stream().map(this::toAiDTO).toList();
    }

    @Override
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
        List<ChatAttachmentEntity> found = mapper.selectAttachedForRetry(
                userId,
                persistedConversationId,
                messageId
        );
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
        // A follow-up may have inherited context without attaching the files again.
        // Keep that original message's temporal boundary when retrying it later.
        validateAttachedOwnership(found, userId, persistedConversationId, messageId, true);
        if (found.isEmpty() && ids.isEmpty()) found = recentAttachments(userId, persistedConversationId, messageId);
        validateLimits(found);
        return found.stream().map(this::toAiDTO).toList();
    }

    @Override
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

    @Override
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

    @Override
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
        List<ChatAttachmentEntity> expired = mapper.selectExpiredReady(LocalDateTime.now(), 100);
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单轮最多 200 个文件，且不能重复");
        }
        if (ids.isEmpty()) return List.of();
        List<ChatAttachmentEntity> found = mapper.selectByUserIdAndIds(userId, ids);
        var byId = found.stream().collect(java.util.stream.Collectors.toMap(ChatAttachmentEntity::getId, item -> item));
        List<ChatAttachmentEntity> ordered = new ArrayList<>();
        for (String id : ids) {
            ChatAttachmentEntity entity = byId.get(id);
            if (entity == null || !userId.equals(entity.getUserId()) || !"READY".equals(entity.getStatus())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "附件未就绪或不属于当前用户");
            }
            ordered.add(entity);
        }
        validateLimits(ordered);
        return ordered;
    }

    private List<ChatAttachmentEntity> recentAttachments(Long userId, Long conversationId, Long atOrBeforeMessageId) {
        List<ChatAttachmentEntity> found = mapper.selectRecentAttachedForConversation(userId, conversationId, atOrBeforeMessageId);
        validateAttachedOwnership(found, userId, conversationId, atOrBeforeMessageId, false);
        validateLimits(found);
        // New images replace the prior attachment group, but are never replayed
        // automatically into unrelated follow-ups such as a weather question.
        return found.stream().filter(entity -> "file".equals(entity.getKind())).toList();
    }

    private void validateAttachedOwnership(List<ChatAttachmentEntity> entities, Long userId, Long conversationId,
                                           Long messageId, boolean exactMessage) {
        if (entities.stream().anyMatch(entity -> !userId.equals(entity.getUserId())
                || !conversationId.equals(entity.getConversationId()) || !"ATTACHED".equals(entity.getStatus())
                || entity.getMessageId() == null || (messageId != null && (exactMessage
                ? !messageId.equals(entity.getMessageId()) : entity.getMessageId() > messageId)))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "附件上下文不属于当前用户、会话或原消息");
        }
    }

    private void validateLimits(List<ChatAttachmentEntity> entities) {
        if (entities.size() > MAX_COUNT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单轮最多 200 个文件");
        }
        long ordinary = entities.stream().filter(entity -> !(StringUtils.hasText(entity.getFolderGroupId())
                && StringUtils.hasText(entity.getFolderRoot()) && StringUtils.hasText(entity.getRelativePath()))).count();
        if (ordinary > MAX_REGULAR_COUNT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单轮普通附件最多 8 个，目录内文件最多 200 个");
        }
        if (entities.stream().filter(entity -> "image".equals(entity.getKind())).count() > MAX_IMAGE_COUNT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单轮图片最多 8 张（含目录内图片）");
        }
        if (entities.stream().anyMatch(entity -> entity.getFileSize() < 0 || entity.getFileSize() > MAX_FILE_BYTES)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单个附件不能超过 5MB");
        }
        if (entities.stream().mapToLong(ChatAttachmentEntity::getFileSize).sum() > MAX_TOTAL_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单轮附件总量不能超过 20MB");
        }
    }

    private ChatAttachmentEntity owned(Long userId, String attachmentId) {
        ChatAttachmentEntity entity = mapper.selectByIdAndUserId(attachmentId, userId);
        if (entity == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "附件不存在");
        return entity;
    }

    private byte[] validateAndRead(MultipartFile file, boolean allowEmpty) {
        if (file.isEmpty() && !allowEmpty) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择非空附件");
        if (file.getSize() > MAX_FILE_BYTES) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单个附件不能超过 5MB");
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "附件读取失败", exception);
        }
    }

    private String contentTypeFor(String name) {
        if (name.startsWith(".") && !TEXT_NAMES.contains(name)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持该附件格式");
        }
        String ext = extensionValue(name);
        if (isTextFile(name)) return "text/plain";
        return switch (ext) {
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持该附件格式");
        };
    }

    private boolean isTextFile(String name) {
        return TEXT_NAMES.contains(name) || (!name.startsWith(".") && TEXT_EXTENSIONS.contains(extensionValue(name)));
    }

    private void validateSignature(byte[] bytes, String contentType, String name) {
        if ("text/plain".equals(contentType)) {
            try {
                String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                if (text.codePoints().anyMatch(value -> (value < 32 && value != '\n' && value != '\r' && value != '\t') || value == 127)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文本附件包含二进制内容，请上传 UTF-8 文本文件");
                }
            } catch (CharacterCodingException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文本附件必须使用 UTF-8 编码", exception);
            }
            return;
        }
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
        String value = raw == null ? "attachment" : Normalizer.normalize(raw, Normalizer.Form.NFC).replace('\\', '/');
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
                base + "?disposition=attachment", entity.getWidth(), entity.getHeight(), entity.getPosition(),
                entity.getSourceType(), entity.getFolderGroupId(), entity.getFolderRoot(), entity.getRelativePath()
        );
    }

    private AiChatAttachmentDTO toAiDTO(ChatAttachmentEntity entity) {
        return new AiChatAttachmentDTO(
                entity.getId(), entity.getKind(), entity.getOriginalName(), entity.getContentType(), entity.getFileSize(),
                entity.getSourceType(), entity.getFolderGroupId(), entity.getFolderRoot(), entity.getRelativePath(),
                new AiChatAttachmentDTO.StorageObjectRefDTO(
                        entity.getStoragePath(), entity.getStorageProvider(), entity.getBucket(), entity.getObjectKey(),
                        entity.getOriginalName(), entity.getContentType(), entity.getFileSize()
                ), entity.getMessageId() == null ? null : String.valueOf(entity.getMessageId())
        );
    }

}
