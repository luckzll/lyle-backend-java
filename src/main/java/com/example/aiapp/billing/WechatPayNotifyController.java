package com.example.aiapp.billing;

import com.example.aiapp.billing.payment.WechatPayPaymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@ConditionalOnProperty(prefix = "app.billing", name = "payment-provider", havingValue = "WECHAT_PAY")
public class WechatPayNotifyController {

    private static final Logger log = LoggerFactory.getLogger(WechatPayNotifyController.class);
    private final WechatPayPaymentGateway paymentGateway;
    private final BillingService billingService;

    public WechatPayNotifyController(
            WechatPayPaymentGateway paymentGateway,
            BillingService billingService
    ) {
        this.paymentGateway = paymentGateway;
        this.billingService = billingService;
    }

    @PostMapping(
            value = "/billing/webhooks/wechat-pay/notify",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> notify(
            @RequestHeader("Wechatpay-Serial") String serialNumber,
            @RequestHeader("Wechatpay-Timestamp") String timestamp,
            @RequestHeader("Wechatpay-Nonce") String nonce,
            @RequestHeader("Wechatpay-Signature") String signature,
            @RequestBody String body
    ) {
        try {
            WechatPayPaymentGateway.VerifiedPaymentNotification notification =
                    paymentGateway.parsePaymentNotification(
                            serialNumber,
                            timestamp,
                            nonce,
                            signature,
                            body
                    );
            billingService.processWechatPayNotification(notification);
            return ResponseEntity.noContent().build();
        } catch (RuntimeException exception) {
            log.warn("Failed to process a WeChat Pay notification after verification or validation.");
            return ResponseEntity.badRequest().body(Map.of(
                    "code", "FAIL",
                    "message", "通知验签或业务校验失败"
            ));
        }
    }
}
