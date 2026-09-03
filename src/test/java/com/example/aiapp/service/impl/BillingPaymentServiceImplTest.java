package com.example.aiapp.service.impl;

import com.example.aiapp.billing.BillingMetrics;
import com.example.aiapp.billing.BillingProperties;
import com.example.aiapp.billing.payment.PaymentGateway;
import com.example.aiapp.mapper.BillingOrderMapper;
import com.example.aiapp.service.BillingOrderQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BillingPaymentServiceImplTest {

    @Test
    void hidesClosedOrderForOwningUserWithoutDeletingAuditData() {
        BillingOrderMapper mapper = mock(BillingOrderMapper.class);
        BillingPaymentServiceImpl service = service(mapper);
        when(mapper.selectOrderForUser(7L, "L-CLOSED", true))
                .thenReturn(Map.of("id", 91L, "status", "CLOSED"));
        when(mapper.markOrderHiddenForUser(eq(91L), eq(7L), any(LocalDateTime.class))).thenReturn(1);

        service.deleteOrder(7L, "L-CLOSED");

        verify(mapper).markOrderHiddenForUser(eq(91L), eq(7L), any(LocalDateTime.class));
        verify(mapper, never()).deleteById(91L);
    }

    @Test
    void refusesToHideOrderBeforeItHasBeenCancelled() {
        BillingOrderMapper mapper = mock(BillingOrderMapper.class);
        BillingPaymentServiceImpl service = service(mapper);
        when(mapper.selectOrderForUser(7L, "L-PENDING", true))
                .thenReturn(Map.of("id", 92L, "status", "PENDING_PAYMENT"));

        assertThatThrownBy(() -> service.deleteOrder(7L, "L-PENDING"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.CONFLICT));
        verify(mapper, never()).markOrderHiddenForUser(anyLong(), anyLong(), any(LocalDateTime.class));
    }

    private static BillingPaymentServiceImpl service(BillingOrderMapper mapper) {
        return new BillingPaymentServiceImpl(
                mapper,
                new ObjectMapper(),
                mock(BillingProperties.class),
                mock(PaymentGateway.class),
                mock(BillingMetrics.class),
                mock(BillingOrderQueryService.class)
        );
    }
}
