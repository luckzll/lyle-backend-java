package com.example.aiapp.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.aiapp.mapper.RefundOrderMapper;
import com.example.aiapp.billing.payment.PaymentGatewayRegistry;
import com.example.aiapp.service.BillingRefundService;
import com.example.aiapp.service.impl.BillingRefundServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class BillingRefundIdempotencyTest {

    @Test
    void successfulRefundReplayReturnsOriginalResultBeforeOrderStatusValidation() {
        RefundOrderMapper refundOrderMapper = mock(RefundOrderMapper.class);
        when(refundOrderMapper.selectOrderByOrderNo("L-REFUNDED", true)).thenReturn(
                Map.of("id", 11L, "status", "REFUNDED")
        );
        when(refundOrderMapper.selectRefundNoByOrderIdAndRequestId(11L, "refund-request-1"))
                .thenReturn("R-ORIGINAL");
        when(refundOrderMapper.selectRefundByRefundNo("R-ORIGINAL", false)).thenReturn(
                Map.of("refund_no", "R-ORIGINAL", "status", "SUCCEEDED")
        );
        BillingRefundService service = new BillingRefundServiceImpl(
                refundOrderMapper,
                new ObjectMapper(),
                mock(BillingProperties.class),
                mock(PaymentGatewayRegistry.class),
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
        var inOrder = inOrder(refundOrderMapper);
        inOrder.verify(refundOrderMapper).selectOrderByOrderNo("L-REFUNDED", true);
        inOrder.verify(refundOrderMapper).selectRefundNoByOrderIdAndRequestId(11L, "refund-request-1");
        inOrder.verify(refundOrderMapper).selectRefundByRefundNo("R-ORIGINAL", false);
        verifyNoMoreInteractions(refundOrderMapper);
    }
}
