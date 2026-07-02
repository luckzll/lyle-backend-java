package com.example.aiapp.infrastructure.storage;

import com.example.aiapp.config.TosProperties;
import com.example.aiapp.domain.vo.ImageUploadVO;
import com.volcengine.tos.TOSV2;
import com.volcengine.tos.TOSV2ClientBuilder;
import com.volcengine.tos.comm.common.ACLType;
import com.volcengine.tos.model.object.ObjectMetaRequestOptions;
import com.volcengine.tos.model.object.PutObjectInput;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

@Component
public class TosImageStorageService {

    private static final long MAX_IMAGE_SIZE = 5L * 1024 * 1024;
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/gif"
    );

    private final TosProperties properties;

    public TosImageStorageService(TosProperties properties) {
        this.properties = properties;
    }

    public ImageUploadVO upload(Long userId, MultipartFile file) {
        validate(file);
        if (!properties.enabled()) {
            throw new ResponseStatusException(BAD_REQUEST, "TOS 上传未启用");
        }
        if (!StringUtils.hasText(properties.accessKeyId()) || !StringUtils.hasText(properties.secretAccessKey())) {
            throw new ResponseStatusException(BAD_REQUEST, "TOS AK/SK 未配置");
        }

        String originalName = file.getOriginalFilename() == null ? "image" : file.getOriginalFilename();
        String contentType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        String objectKey = objectKey(userId, originalName, contentType);
        try (TOSV2 client = new TOSV2ClientBuilder().build(
                properties.region(),
                properties.endpoint(),
                properties.accessKeyId(),
                properties.secretAccessKey()
        )) {
            client.putObject(PutObjectInput.builder()
                    .bucket(properties.bucket())
                    .key(objectKey)
                    .content(file.getInputStream())
                    .contentLength(file.getSize())
                    .options(ObjectMetaRequestOptions.builder()
                            .contentType(contentType)
                            .aclType(ACLType.ACL_PUBLIC_READ)
                            .build())
                    .build());
            return new ImageUploadVO(
                    originalName,
                    publicUrl(objectKey),
                    objectKey,
                    contentType,
                    file.getSize()
            );
        } catch (IOException exception) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "读取图片失败", exception);
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "上传图片到 TOS 失败：" + exception.getMessage(), exception);
        }
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "请选择图片文件");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new ResponseStatusException(BAD_REQUEST, "仅支持 JPG、PNG、WEBP、GIF 图片");
        }
        if (file.getSize() > MAX_IMAGE_SIZE) {
            throw new ResponseStatusException(BAD_REQUEST, "图片不能超过 5MB");
        }
    }

    private String objectKey(Long userId, String originalName, String contentType) {
        String extension = extension(originalName, contentType);
        String prefix = properties.savePathHead() == null ? "" : properties.savePathHead().replaceAll("^/+|/+$", "");
        String key = "images/" + userId + "/" + UUID.randomUUID() + extension;
        return prefix.isBlank() ? key : prefix + "/" + key;
    }

    private String extension(String originalName, String contentType) {
        String cleanName = originalName == null ? "" : originalName;
        int dot = cleanName.lastIndexOf('.');
        if (dot >= 0 && dot < cleanName.length() - 1) {
            String ext = cleanName.substring(dot).toLowerCase(Locale.ROOT);
            if (ext.matches("\\.(jpg|jpeg|png|webp|gif)")) {
                return ext;
            }
        }
        return switch (contentType.toLowerCase(Locale.ROOT)) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> ".jpg";
        };
    }

    private String publicUrl(String objectKey) {
        return properties.publicUrlHead().replaceAll("/+$", "") + "/" + objectKey;
    }
}
