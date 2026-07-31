package com.example.aiapp.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatAttachmentPathPolicyTest {

    @Test
    void normalizesValidFolderMetadata() {
        var result = ChatAttachmentPathPolicy.normalize(
                "folder_drop", "group-1", "需求资料", "需求资料\\版本\\范围.md", "范围.md"
        );

        assertThat(result.relativePath()).isEqualTo("需求资料/版本/范围.md");
        assertThat(result.folderRoot()).isEqualTo("需求资料");
    }

    @Test
    void rejectsTraversalAbsoluteUrisAndInvalidSegments() {
        for (String path : new String[]{
                "需求资料/../密码.txt",
                "/tmp/密码.txt",
                "C:/tmp/密码.txt",
                "file://tmp/密码.txt",
                "file:/tmp/密码.txt",
                "file:tmp/密码.txt",
                "jar:file:/tmp/密码.txt",
                " file:/tmp/密码.txt",
                "需求资料//密码.txt",
                "需求资料/坏\n名字.txt"
        }) {
            assertThatThrownBy(() -> ChatAttachmentPathPolicy.normalize(
                    "folder_drop", "group-1", "需求资料", path, "密码.txt"
            )).isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("附件目录信息无效");
        }
    }

    @Test
    void rejectsFolderSourceWithoutCompleteFolderMetadata() {
        assertThatThrownBy(() -> ChatAttachmentPathPolicy.normalize(
                "folder_picker", null, null, null, "范围.md"
        )).isInstanceOf(ResponseStatusException.class);
    }
}
