package com.example.aiapp.service;

import com.example.aiapp.domain.entity.ChatAttachmentEntity;
import com.example.aiapp.infrastructure.storage.FileStorageService;
import com.example.aiapp.infrastructure.storage.StoredObject;
import com.example.aiapp.mapper.ChatAttachmentMapper;
import com.example.aiapp.service.impl.ChatAttachmentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
