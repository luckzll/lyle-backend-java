package com.example.aiapp.billing;

import com.example.aiapp.billing.payment.WechatPayPaymentGateway;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WechatPayNotifyControllerTest {

    @Test
    void verifiedNotificationReturnsNoContentAndReachesBillingService() {
        WechatPayPaymentGateway gateway = mock(WechatPayPaymentGateway.class);
        BillingService billingService = mock(BillingService.class);
        WechatPayPaymentGateway.VerifiedPaymentNotification notification =
                new WechatPayPaymentGateway.VerifiedPaymentNotification(
                        "event-1",
                        "payment-1",
                        "SUCCEEDED",
                        "transaction-1",
                        990,
                        "CNY",
                        "payload-hash"
                );
        when(gateway.parsePaymentNotification("serial", "timestamp", "nonce", "signature", "{}"))
                .thenReturn(notification);

        ResponseEntity<?> response = new WechatPayNotifyController(gateway, billingService)
                .notify("serial", "timestamp", "nonce", "signature", "{}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(billingService).processWechatPayNotification(notification);
    }

    @Test
    void invalidSignatureOrPayloadReturnsWechatFailureBody() {
        WechatPayPaymentGateway gateway = mock(WechatPayPaymentGateway.class);
        BillingService billingService = mock(BillingService.class);
        when(gateway.parsePaymentNotification("serial", "timestamp", "nonce", "bad", "{}"))
                .thenThrow(new IllegalArgumentException("invalid"));

        ResponseEntity<?> response = new WechatPayNotifyController(gateway, billingService)
                .notify("serial", "timestamp", "nonce", "bad", "{}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
    }
}
