package com.example.aiapp.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.aiapp.service.BillingRefundService;
import com.example.aiapp.service.impl.BillingRefundServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class BillingRefundIdempotencyTest {

    @Test
    void successfulRefundReplayReturnsOriginalResultBeforeOrderStatusValidation() {
        RefundReplayJdbcTemplate jdbcTemplate = new RefundReplayJdbcTemplate();
        BillingRefundService service = new BillingRefundServiceImpl(
                jdbcTemplate,
                new ObjectMapper(),
                mock(BillingProperties.class),
                mock(com.example.aiapp.billing.payment.PaymentGateway.class),
                mock(BillingMetrics.class)
        );

        Map<String, Object> response = service.createRefund(
                7L,
                "L-REFUNDED",
                new BillingRequests.CreateRefund("refund-request-1", "USER_REQUEST", "retry")
        );

        assertThat(response)
                .containsEntry("refund_no", "R-ORIGINAL")
                .containsEntry("status", "SUCCEEDED");
        assertThat(jdbcTemplate.queryStages)
                .containsExactly("order", "idempotency", "refund");
        assertThat(jdbcTemplate.updateCount).isZero();
    }

    private static final class RefundReplayJdbcTemplate extends JdbcTemplate {
        private final List<String> queryStages = new ArrayList<>();
        private int updateCount;

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (sql.contains("SELECT * FROM billing_order")) {
                queryStages.add("order");
                return List.of(Map.of("id", 11L, "status", "REFUNDED"));
            }
            if (sql.contains("SELECT refund_no FROM refund_order")) {
                queryStages.add("idempotency");
                return List.of(Map.of("refund_no", "R-ORIGINAL"));
            }
            if (sql.contains("SELECT r.*, o.order_no")) {
                queryStages.add("refund");
                return List.of(Map.of(
                        "refund_no", "R-ORIGINAL",
                        "status", "SUCCEEDED"
                ));
            }
            throw new AssertionError("Unexpected query: " + sql);
        }

        @Override
        public int update(String sql, Object... args) {
            updateCount++;
            throw new AssertionError("Refund replay must not mutate data: " + sql);
        }
    }
}
