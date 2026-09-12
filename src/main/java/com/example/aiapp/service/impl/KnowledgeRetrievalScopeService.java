package com.example.aiapp.service.impl;

import com.example.aiapp.domain.entity.KnowledgeBaseEntity;
import com.example.aiapp.domain.entity.KnowledgeFileEntity;
import com.example.aiapp.mapper.KnowledgeBaseMapper;
import com.example.aiapp.mapper.KnowledgeFileMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/** Fresh, server-owned manifest: cached retrieval never supplies its own authorization. */
@Service
public class KnowledgeRetrievalScopeService {
    private final KnowledgeBaseMapper bases;
    private final KnowledgeFileMapper files;

    public KnowledgeRetrievalScopeService(KnowledgeBaseMapper bases, KnowledgeFileMapper files) {
        this.bases = bases;
        this.files = files;
    }

    public Map<String, Object> build(Long userId, List<String> ids, boolean admin) {
        if (ids == null || ids.isEmpty()) return Map.of();
        if (userId == null) throw new ResponseStatusException(NOT_FOUND, "知识库不存在");
        Map<String, String> revisions = new TreeMap<>();
        Map<String, List<String>> allowed = new TreeMap<>();
        Map<String, Map<String, String>> documentRevisions = new TreeMap<>();
        List<String> ownership = new ArrayList<>(List.of(String.valueOf(userId)));
        for (String rawId : new TreeSet<>(ids)) {
            long id;
            try {
                id = Long.parseLong(rawId);
            } catch (RuntimeException exception) {
                throw new ResponseStatusException(NOT_FOUND, "知识库不存在");
            }
            KnowledgeBaseEntity base = admin ? bases.selectById(id) : bases.selectByIdAndUserId(id, userId);
            if (base == null || (!admin && !Objects.equals(base.getUserId(), userId))) {
                throw new ResponseStatusException(NOT_FOUND, "知识库不存在");
            }
            String kb = String.valueOf(id);
            List<KnowledgeFileEntity> entries = new ArrayList<>(files.selectByKnowledgeBaseId(id));
            entries.removeIf(file -> file.getId() == null || !Objects.equals(file.getKnowledgeBaseId(), id));
            entries.sort(Comparator.comparing(KnowledgeFileEntity::getId));
            List<String> manifest = new ArrayList<>(List.of(kb, String.valueOf(base.getUserId())));
            for (KnowledgeFileEntity file : entries) {
                Collections.addAll(manifest, String.valueOf(file.getId()), file.getParseStatus(),
                        file.getRetrievalRevision(), file.getStorageProvider(), file.getStoragePath(),
                        file.getBucket(), file.getObjectKey(), String.valueOf(file.getFileSize()));
            }
            revisions.put(rawId, digest(manifest));
            allowed.put(rawId, entries.stream().filter(file -> "completed".equals(file.getParseStatus()))
                    .map(file -> String.valueOf(file.getId())).toList());
            Map<String, String> fileRevisions = new TreeMap<>();
            entries.stream().filter(file -> "completed".equals(file.getParseStatus())).forEach(file ->
                    fileRevisions.put(String.valueOf(file.getId()), Objects.toString(file.getRetrievalRevision(), "legacy")));
            documentRevisions.put(rawId, fileRevisions);
            ownership.add(kb + ":" + base.getUserId());
        }
        return Map.of("userId", String.valueOf(userId), "authorizationRevision", digest(ownership),
                "knowledgeBaseRevisions", revisions, "allowedDocumentIds", allowed,
                "documentRevisions", documentRevisions);
    }

    private static String digest(List<String> values) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = Objects.toString(value, "").getBytes(StandardCharsets.UTF_8);
                hash.update((bytes.length + ":").getBytes(StandardCharsets.US_ASCII));
                hash.update(bytes);
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
