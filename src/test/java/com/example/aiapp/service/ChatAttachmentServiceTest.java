package com.example.aiapp.service;

import com.example.aiapp.domain.entity.ChatAttachmentEntity;
import com.example.aiapp.infrastructure.storage.FileStorageService;
import com.example.aiapp.infrastructure.storage.StoredObject;
import com.example.aiapp.mapper.ChatAttachmentMapper;
import com.example.aiapp.service.impl.ChatAttachmentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

class ChatAttachmentServiceTest {

    private ChatAttachmentMapper mapper;
    private FileStorageService storage;
    private ChatAttachmentService service;

    @BeforeEach
    void setUp() {
        mapper = mock(ChatAttachmentMapper.class);
        storage = mock(FileStorageService.class);
        service = new ChatAttachmentServiceImpl(mapper, storage);
    }

    @Test
    void uploadCreatesPrivateReadyAttachment() {
        byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        var file = new MockMultipartFile("file", "reference.png", "image/png", png);
        when(storage.upload(anyString(), anyString(), any(), anyLong(), anyString())).thenReturn(
                new StoredObject("LOCAL", "chat-attachments", "42/object.png", "file:///tmp/object.png", "image/png", png.length, "private")
        );

        var result = service.upload(42L, file, "image");

        assertThat(result.kind()).isEqualTo("image");
        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.previewUrl()).contains(result.id());
        verify(mapper).insert(any(ChatAttachmentEntity.class));
    }

    @Test
    void uploadPersistsFolderMetadataWithoutUsingItAsObjectKey() {
        byte[] text = "scope".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var file = new MockMultipartFile("file", "范围.md", "text/markdown", text);
        when(storage.upload(anyString(), anyString(), any(), anyLong(), anyString())).thenReturn(
                new StoredObject("LOCAL", "chat-attachments", "42/server-id.md", "file:///tmp/server-id.md", "text/markdown", text.length, "private")
        );

        var result = service.upload(42L, file, "file", "folder_drop", "group-1", "需求资料", "需求资料/版本/范围.md");

        assertThat(result.relativePath()).isEqualTo("需求资料/版本/范围.md");
        assertThat(result.folderGroupId()).isEqualTo("group-1");
        verify(storage).upload(anyString(), org.mockito.ArgumentMatchers.matches("42/[0-9a-f-]+\\.md"), any(), anyLong(), anyString());
    }

    @Test
    void uploadRejectsExecutableDisguisedAsFile() {
        var file = new MockMultipartFile("file", "payload.exe", "application/octet-stream", new byte[]{1, 2, 3});

        assertThatThrownBy(() -> service.upload(42L, file, "file"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("不支持该附件格式");
    }

    @Test
    void readyAttachmentsPreserveRequestedOrder() {
        ChatAttachmentEntity first = ready("first", 10L);
        ChatAttachmentEntity second = ready("second", 20L);
        when(mapper.selectByUserIdAndIds(anyLong(), any())).thenReturn(List.of(first, second));

        var result = service.requireReady(42L, List.of("second", "first"));

        assertThat(result).extracting(item -> item.id()).containsExactly("second", "first");
    }

    @Test
    void retryRequiresEveryOriginalAttachmentInOriginalOrder() {
        ChatAttachmentEntity first = attached("first", 10L, 0);
        ChatAttachmentEntity second = attached("second", 20L, 1);
        when(mapper.selectAttachedForRetry(anyLong(), anyLong(), anyLong())).thenReturn(List.of(first, second));

        assertThatThrownBy(() -> service.requireAttachedForRetry(
                42L,
                "100",
                98L,
                List.of("first")
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("全部附件和顺序");

        var result = service.requireAttachedForRetry(
                42L,
                "100",
                98L,
                List.of("first", "second")
        );
        assertThat(result).extracting(item -> item.id()).containsExactly("first", "second");
    }

    @ParameterizedTest
    @ValueSource(strings = {"main.ts", "main.tsx", "main.js", "main.jsx", "main.mjs", "main.cjs", "App.vue", "App.svelte",
            "main.py", "Main.java", "Main.kt", "build.kts", "main.go", "main.rs", "main.c", "main.h", "main.cpp", "main.hpp",
            "Main.cs", "main.php", "main.rb", "Main.swift", "run.sh", "run.bash", "run.zsh", "run.ps1", "schema.sql",
            "app.yaml", "app.yml", "app.toml", "app.ini", "app.cfg", "pom.xml", "index.html", "index.htm", "app.css",
            "app.scss", "app.sass", "app.less", "app.graphql", "app.gql", "app.proto", "app.properties", "build.gradle",
            "Main.scala", "main.r", "main.dart", "main.ipynb", "Dockerfile", "Makefile", "Containerfile", "Jenkinsfile",
            "Procfile", "README", "LICENSE", ".gitignore", ".dockerignore", ".editorconfig", "README.md", "data.json"})
    void sourceFilesUseOriginalNameAndValidatedUtf8Text(String name) {
        byte[] text = "# 示例🙂\nprint('ok')\t\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(storage.upload(anyString(), anyString(), any(), anyLong(), anyString())).thenReturn(
                new StoredObject("LOCAL", "chat-attachments", "42/server-id", "file:///tmp/server-id", "text/plain", text.length, "private"));
        var result = service.upload(42L, new MockMultipartFile("file", name, "application/octet-stream", text), "file",
                "folder_drop", "group", "project", "project/" + name);
        assertThat(result.mimeType()).isEqualTo("text/plain");
        assertThat(result.name()).isEqualTo(name);
        assertThat(result.relativePath()).isEqualTo("project/" + name);
        verify(storage).upload(anyString(), anyString(), any(), anyLong(), org.mockito.ArgumentMatchers.eq("text/plain"));
    }

    @Test
    void permitsEmptySourceFilesButRejectsEmptyDocumentsBinaryAndInvalidUtf8() {
        when(storage.upload(anyString(), anyString(), any(), anyLong(), anyString())).thenReturn(
                new StoredObject("LOCAL", "chat-attachments", "42/empty.py", "file:///tmp/empty.py", "text/plain", 0, "private"));
        assertThat(service.upload(42L, new MockMultipartFile("file", "empty.py", "text/plain", new byte[0]), "file").size()).isZero();
        assertThatThrownBy(() -> service.upload(42L, new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]), "file"))
                .hasMessageContaining("非空");
        assertThatThrownBy(() -> service.upload(42L, new MockMultipartFile("file", "main.py", "text/plain", new byte[]{0, 1, 2}), "file"))
                .hasMessageContaining("二进制");
        assertThatThrownBy(() -> service.upload(42L, new MockMultipartFile("file", "main.py", "text/plain", new byte[]{(byte) 0xc3, 0x28}), "file"))
                .hasMessageContaining("UTF-8");
    }

    @Test
    void doesNotTrustClientMimeOrKindToBypassFileTypeAndImageLimits() {
        assertThatThrownBy(() -> service.upload(42L, new MockMultipartFile("file", "payload.exe", "image/png",
                new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47}), "image")).hasMessageContaining("不支持");
        assertThatThrownBy(() -> service.upload(42L, new MockMultipartFile("file", "main.py", "image/png",
                "print(1)".getBytes()), "image")).hasMessageContaining("类型");
        assertThatThrownBy(() -> service.upload(42L, new MockMultipartFile("file", "image.png", "text/plain",
                "not an image".getBytes()), "file")).hasMessageContaining("类型");
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {".env", ".env.json", ".npmrc", ".private.pdf", "payload.exe", "project.zip"})
    void rejectsUnapprovedHiddenFilesAndBinaryFormats(String name) {
        assertThatThrownBy(() -> service.upload(42L,
                new MockMultipartFile("file", name, "text/plain", "content".getBytes()), "file"))
                .hasMessageContaining("不支持该附件格式");
        verifyNoInteractions(storage);
    }

    @Test
    void enforcesIndependentDirectoryOrdinaryImageAndByteLimits() {
        List<ChatAttachmentEntity> entities = new ArrayList<>();
        for (int index = 0; index < 200; index++) {
            ChatAttachmentEntity entity = ready("file-" + index, 1);
            if (index >= 8) directory(entity);
            entities.add(entity);
        }
        when(mapper.selectByUserIdAndIds(anyLong(), any())).thenAnswer(invocation -> entities);
        List<String> ids = entities.stream().map(ChatAttachmentEntity::getId).toList();
        assertThat(service.requireReady(42L, ids)).hasSize(200);
        entities.get(8).setFolderGroupId(null);
        assertThatThrownBy(() -> service.requireReady(42L, ids)).hasMessageContaining("普通附件最多 8");
        directory(entities.get(8));
        List<String> tooMany = new ArrayList<>(ids);
        tooMany.add("overflow");
        assertThatThrownBy(() -> service.requireReady(42L, tooMany)).hasMessageContaining("200");
        for (int index = 0; index < 9; index++) entities.get(index).setKind("image");
        assertThatThrownBy(() -> service.requireReady(42L, ids)).hasMessageContaining("图片最多 8");
        entities.forEach(entity -> entity.setKind("file"));
        entities.get(0).setFileSize(5 * 1024 * 1024L + 1);
        assertThatThrownBy(() -> service.requireReady(42L, ids)).hasMessageContaining("5MB");
        for (int index = 0; index < 4; index++) entities.get(index).setFileSize(5 * 1024 * 1024L);
        assertThatThrownBy(() -> service.requireReady(42L, ids)).hasMessageContaining("20MB");
    }

    @Test
    void inheritedContextIsOwnedScopedAndContainsOnlyFilesFromTheLatestAttachmentMessage() {
        ChatAttachmentEntity document = attached("source", 10, 0);
        ChatAttachmentEntity image = attached("image", 10, 1);
        image.setKind("image");
        when(mapper.selectRecentAttachedForConversation(42L, 100L, null)).thenReturn(List.of(document, image));
        var inherited = service.requireRecentForConversation(42L, "100");
        assertThat(inherited).extracting(item -> item.id()).containsExactly("source");
        assertThat(inherited.get(0).sourceMessageId()).isEqualTo("98");
        when(mapper.selectRecentAttachedForConversation(42L, 100L, null)).thenReturn(List.of(image));
        assertThat(service.requireRecentForConversation(42L, "100")).isEmpty();
        assertThat(service.requireRecentForConversation(42L, "101")).isEmpty();
        assertThat(service.requireRecentForConversation(7L, "100")).isEmpty();
        assertThat(service.requireRecentForConversation(42L, "temporary-id")).isEmpty();
        when(mapper.selectRecentAttachedForConversation(42L, 100L, null)).thenReturn(List.of(document));
        document.setUserId(7L);
        assertThatThrownBy(() -> service.requireRecentForConversation(42L, "100")).hasMessageContaining("当前用户");
        document.setUserId(42L);
        document.setConversationId(101L);
        assertThatThrownBy(() -> service.requireRecentForConversation(42L, "100")).hasMessageContaining("会话");
    }

    @Test
    void inheritedRetryIsBoundedByOriginalMessageAndCannotUseLaterUploads() {
        ChatAttachmentEntity document = attached("source", 10, 0);
        when(mapper.selectRecentAttachedForConversation(42L, 100L, 150L)).thenReturn(List.of(document));
        assertThat(service.requireAttachedForRetry(42L, "100", 150L, List.of())).extracting(item -> item.id()).containsExactly("source");
        verify(mapper, never()).selectRecentAttachedForConversation(42L, 100L, null);
        document.setMessageId(160L);
        assertThatThrownBy(() -> service.requireAttachedForRetry(42L, "100", 150L, List.of())).hasMessageContaining("原消息");
    }

    private void directory(ChatAttachmentEntity entity) {
        entity.setSourceType("folder_drop");
        entity.setFolderGroupId("group");
        entity.setFolderRoot("project");
        entity.setRelativePath("project/" + entity.getOriginalName());
    }

    private ChatAttachmentEntity ready(String id, long size) {
        ChatAttachmentEntity entity = new ChatAttachmentEntity();
        entity.setId(id);
        entity.setUserId(42L);
        entity.setKind("file");
        entity.setStatus("READY");
        entity.setOriginalName(id + ".txt");
        entity.setContentType("text/plain");
        entity.setFileSize(size);
        entity.setStorageProvider("LOCAL");
        entity.setStoragePath("file:///tmp/" + id);
        entity.setBucket("chat-attachments");
        entity.setObjectKey("42/" + id);
        return entity;
    }

    private ChatAttachmentEntity attached(String id, long size, int position) {
        ChatAttachmentEntity entity = ready(id, size);
        entity.setStatus("ATTACHED");
        entity.setConversationId(100L);
        entity.setMessageId(98L);
        entity.setPosition(position);
        return entity;
    }
}
