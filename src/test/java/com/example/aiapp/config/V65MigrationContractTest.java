package com.example.aiapp.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class V65MigrationContractTest {

    @Test
    void commercialLedgerMigrationKeepsUsageAndMoneyLedgersSeparate() throws Exception {
        String sql = resource("/db/migration/V26__add_billing_catalog_and_commercial_ledger.sql");

        assertThat(sql)
                .contains("CREATE TABLE billing_product")
                .contains("CREATE TABLE billing_price_version")
                .contains("CREATE TABLE commercial_account")
                .contains("CREATE TABLE entitlement_grant")
                .contains("CREATE TABLE commercial_ledger_entry")
                .contains("CREATE TABLE billable_usage")
                .contains("UNIQUE KEY uk_commercial_ledger_idempotency")
                .contains("UNIQUE KEY uk_billable_usage_event")
                .contains("balance_after_micros BIGINT NOT NULL")
                .doesNotContain("estimated_cost_micros");
    }

    @Test
    void paymentMigrationContainsRecoverableAndIdempotentStages() throws Exception {
        String sql = resource("/db/migration/V27__add_orders_payments_refunds_and_reconciliation.sql");

        assertThat(sql)
                .contains("CREATE TABLE billing_order")
                .contains("CREATE TABLE payment_attempt")
                .contains("CREATE TABLE payment_webhook_event")
                .contains("CREATE TABLE refund_order")
                .contains("CREATE TABLE billing_outbox")
                .contains("CREATE TABLE payment_reconciliation_item")
                .contains("UNIQUE KEY uk_billing_order_user_request")
                .contains("UNIQUE KEY uk_payment_webhook_event")
                .contains("UNIQUE KEY uk_billing_outbox_event");
    }

    private static String resource(String path) throws Exception {
        try (var stream = V65MigrationContractTest.class.getResourceAsStream(path)) {
            assertThat(stream).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
