package com.example.aiapp.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class BillingOrderVisibilityMigrationContractTest {

    @Test
    void addsSoftDeleteMarkerWithoutRemovingFinancialRecords() throws Exception {
        String sql = new ClassPathResource(
                "db/migration/V30__add_billing_order_user_visibility.sql"
        ).getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql).contains("user_hidden_at");
        assertThat(sql.toUpperCase()).doesNotContain("DELETE FROM BILLING_ORDER");
    }
}
