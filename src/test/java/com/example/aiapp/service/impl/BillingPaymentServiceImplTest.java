package com.example.aiapp.service.impl;

import com.example.aiapp.billing.BillingMetrics;
import com.example.aiapp.billing.BillingProperties;
import com.example.aiapp.billing.payment.PaymentGateway;
import com.example.aiapp.billing.payment.PaymentGatewayRegistry;
import com.example.aiapp.mapper.BillingOrderMapper;
import com.example.aiapp.service.BillingOrderQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
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
    void switchesFromAlipayToWechatWithoutClosingTheOrder() {
        BillingOrderMapper mapper = mock(BillingOrderMapper.class);
        BillingProperties properties = properties("ALIPAY,WECHAT_PAY");
        PaymentGateway alipay = mock(PaymentGateway.class);
        PaymentGateway wechat = mock(PaymentGateway.class);
        when(alipay.provider()).thenReturn("ALIPAY");
        when(wechat.provider()).thenReturn("WECHAT_PAY");
        when(alipay.queryPayment("P-ALIPAY"))
                .thenReturn(new PaymentGateway.PaymentResult("PENDING", null));
        when(alipay.closePayment("P-ALIPAY"))
                .thenReturn(new PaymentGateway.PaymentResult("CLOSED", null));
        when(wechat.createPayment(any(PaymentGateway.CreatePaymentCommand.class)))
                .thenAnswer(invocation -> {
                    PaymentGateway.CreatePaymentCommand command = invocation.getArgument(0);
                    return new PaymentGateway.CheckoutResult(
                            "WECHAT_PAY", "PENDING", "QR_CODE", "weixin://pay", command.expiresAt()
                    );
                });
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("id", 100L);
        order.put("order_no", "L-ORDER");
        order.put("status", "PENDING_PAYMENT");
        order.put("currency", "CNY");
        order.put("payable_amount_minor", 10L);
        order.put("product_snapshot", "{\"name\":\"轻享版\"}");
        order.put("expires_at", LocalDateTime.now().plusMinutes(20));
        Map<String, Object> oldPayment = Map.of(
                "id", 200L,
                "order_id", 100L,
                "payment_no", "P-ALIPAY",
                "provider", "ALIPAY",
                "status", "PENDING",
                "currency", "CNY",
                "amount_minor", 10L,
                "expires_at", LocalDateTime.now().plusMinutes(20)
        );
        Map<String, Object> newPayment = Map.of(
                "id", 201L,
                "order_id", 100L,
                "payment_no", "P-WECHAT",
                "provider", "WECHAT_PAY",
                "status", "PENDING",
                "currency", "CNY",
                "amount_minor", 10L,
                "expires_at", LocalDateTime.now().plusMinutes(20)
        );
        when(mapper.selectOrderForUser(7L, "L-ORDER", true)).thenReturn(order);
        when(mapper.selectLatestPaymentByOrderId(100L)).thenReturn(oldPayment, newPayment);
        BillingPaymentServiceImpl service = new BillingPaymentServiceImpl(
                mapper,
                new ObjectMapper(),
                properties,
                new PaymentGatewayRegistry(properties, List.of(alipay, wechat)),
                mock(BillingMetrics.class),
                mock(BillingOrderQueryService.class)
        );

        Map<String, Object> response = service.checkout(7L, "L-ORDER", "WECHAT_PAY");

        assertThat(response)
                .containsEntry("provider", "WECHAT_PAY")
                .containsEntry("checkoutType", "QR_CODE")
                .containsEntry("availableProviders", List.of("ALIPAY", "WECHAT_PAY"));
        verify(mapper).markPaymentClosed(eq(200L), any(LocalDateTime.class));
        verify(mapper, never()).markOrderClosed(eq(100L), any(LocalDateTime.class));
    }

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
        BillingProperties properties = properties("MOCK");
        return new BillingPaymentServiceImpl(
                mapper,
                new ObjectMapper(),
                properties,
                new PaymentGatewayRegistry(properties, List.of(mockGateway("MOCK"))),
                mock(BillingMetrics.class),
                mock(BillingOrderQueryService.class)
        );
    }

    private static PaymentGateway mockGateway(String provider) {
        PaymentGateway gateway = mock(PaymentGateway.class);
        when(gateway.provider()).thenReturn(provider);
        return gateway;
    }

    private static BillingProperties properties(String providers) {
        return new BillingProperties(
                true, true, false, false, true, true, true, false, true,
                "CN", "CNY", providers, "", "", "", 30, 10, 1_000_000, 100_000_000
        );
    }
}
