package com.example.aiapp.service;

import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 对话附件路径规则。
 */
public final class ChatAttachmentPathPolicy {

    private static final int MAX_PATH_LENGTH = 512;
    private static final int MAX_SEGMENT_LENGTH = 128;
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x1f\\x7f]");
    private static final Pattern URI_SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:.*");
    private static final Pattern GROUP_ID = Pattern.compile("^[a-zA-Z0-9._-]{1,64}$");
    private static final Set<String> SOURCES = Set.of("picker", "drop", "paste", "folder_picker", "folder_drop");

    private ChatAttachmentPathPolicy() {
    }

    public static FolderMetadata normalize(
            String sourceType,
            String folderGroupId,
            String folderRoot,
            String relativePath,
            String fileName
    ) {
        String source = normalizeSource(sourceType);
        boolean hasFolderValue = StringUtils.hasText(folderGroupId)
                || StringUtils.hasText(folderRoot)
                || StringUtils.hasText(relativePath);
        if (!hasFolderValue) {
            if (source != null && source.startsWith("folder_")) reject();
            return new FolderMetadata(source, null, null, null);
        }
        if (!StringUtils.hasText(folderGroupId) || !StringUtils.hasText(folderRoot) || !StringUtils.hasText(relativePath)) {
            reject();
        }
        String groupId = Normalizer.normalize(folderGroupId.trim(), Normalizer.Form.NFC);
        if (!GROUP_ID.matcher(groupId).matches()) reject();

        String root = normalizeSegment(folderRoot);
        String path = normalizePath(relativePath);
        String[] segments = path.split("/", -1);
        if (!segments[0].equals(root) || !segments[segments.length - 1].equals(fileName)) reject();
        if (source == null || !source.startsWith("folder_")) reject();
        return new FolderMetadata(source, groupId, root, path);
    }

    static String normalizePath(String raw) {
        if (!StringUtils.hasText(raw)) reject();
        String value = Normalizer.normalize(raw, Normalizer.Form.NFC).replace('\\', '/');
        if (!value.equals(value.strip()) || value.length() > MAX_PATH_LENGTH || value.startsWith("/") || value.startsWith("//")
                || URI_SCHEME.matcher(value).matches() || CONTROL.matcher(value).find()) {
            reject();
        }
        String[] segments = value.split("/", -1);
        if (segments.length == 0) reject();
        for (String segment : segments) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)
                    || segment.length() > MAX_SEGMENT_LENGTH || CONTROL.matcher(segment).find()) {
                reject();
            }
        }
        return String.join("/", segments);
    }

    private static String normalizeSegment(String raw) {
        String value = normalizePath(raw);
        if (value.contains("/")) reject();
        return value;
    }

    private static String normalizeSource(String raw) {
        if (!StringUtils.hasText(raw)) return null;
        String source = raw.trim().toLowerCase(Locale.ROOT);
        if (!SOURCES.contains(source)) reject();
        return source;
    }

    private static void reject() {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "附件目录信息无效");
    }

    public record FolderMetadata(String sourceType, String folderGroupId, String folderRoot, String relativePath) {
    }
}
