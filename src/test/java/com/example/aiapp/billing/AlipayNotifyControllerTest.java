package com.example.aiapp.billing;

import com.example.aiapp.billing.payment.AlipayPaymentGateway;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlipayNotifyControllerTest {

    @Test
    void verifiedMerchantNotificationReturnsExactSuccess() {
        AlipayPaymentGateway gateway = mock(AlipayPaymentGateway.class);
        BillingService billingService = mock(BillingService.class);
        MockHttpServletRequest request = notificationRequest();
        when(gateway.verifyNotification(anyMap())).thenReturn(true);
        when(gateway.notificationOwnerMatches(anyMap())).thenReturn(true);
        when(billingService.processAlipayNotification(anyMap())).thenReturn(true);

        String response = new AlipayNotifyController(gateway, billingService).notify(request);

        assertThat(response).isEqualTo("success");
        verify(billingService).processAlipayNotification(Map.of(
                "notify_id", "notify-1",
                "out_trade_no", "payment-1",
                "trade_status", "TRADE_SUCCESS"
        ));
    }

    @Test
    void invalidSignatureDoesNotReachBillingServiceAndReturnsFail() {
        AlipayPaymentGateway gateway = mock(AlipayPaymentGateway.class);
        BillingService billingService = mock(BillingService.class);
        MockHttpServletRequest request = notificationRequest();
        when(gateway.verifyNotification(anyMap())).thenReturn(false);

        String response = new AlipayNotifyController(gateway, billingService).notify(request);

        assertThat(response).isEqualTo("fail");
        verify(billingService, never()).processAlipayNotification(anyMap());
    }

    private static MockHttpServletRequest notificationRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("notify_id", "notify-1");
        request.setParameter("out_trade_no", "payment-1");
        request.setParameter("trade_status", "TRADE_SUCCESS");
        return request;
    }
}
