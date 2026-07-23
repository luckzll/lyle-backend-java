package com.example.aiapp.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class V23MigrationRollbackContractTest {

    @Test
    void v23RemainsAdditiveSoV62CanRunWithoutAReverseMigration() throws Exception {
        String sql = new ClassPathResource(
                "db/migration/V23__add_chat_attachment_folder_metadata.sql"
        ).getContentAsString(StandardCharsets.UTF_8).toUpperCase();

        assertThat(sql).contains(
                "SOURCE_TYPE VARCHAR(32) NULL",
                "FOLDER_GROUP_ID VARCHAR(64) NULL",
                "FOLDER_ROOT VARCHAR(255) NULL",
                "RELATIVE_PATH VARCHAR(1024) NULL"
        );
        assertThat(sql).doesNotContain("DROP ", "RENAME ", " NOT NULL");
    }
}
