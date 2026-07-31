package com.example.aiapp.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class V64MigrationRollbackContractTest {

    @Test
    void v24AndV25RemainAdditiveForApplicationRollback() throws Exception {
        String sessionSql = migration("V24__add_auth_sessions_and_account_security.sql");
        String usageSql = migration("V25__add_ai_usage_ledger_and_reservations.sql");

        assertThat(sessionSql).contains(
                "ADD COLUMN STATUS",
                "CREATE TABLE AUTH_SESSION",
                "CREATE TABLE AUTH_REFRESH_TOKEN",
                "CREATE TABLE AUTH_SECURITY_EVENT"
        );
        assertThat(usageSql).contains(
                "CREATE TABLE AI_USAGE_EVENT",
                "CREATE TABLE AI_USAGE_POLICY",
                "CREATE TABLE AI_USAGE_RESERVATION"
        );
        assertThat(sessionSql + usageSql).doesNotContain(
                "DROP ",
                "RENAME ",
                "TRUNCATE ",
                "DELETE FROM ",
                "UPDATE APP_USER "
        );
    }

    private static String migration(String name) throws Exception {
        return new ClassPathResource("db/migration/" + name)
                .getContentAsString(StandardCharsets.UTF_8)
                .toUpperCase();
    }
}
