package com.example.aiapp.billing;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/billing/webhooks")
public class BillingWebhookController {

    private final BillingService billingService;

    public BillingWebhookController(BillingService billingService) {
        this.billingService = billingService;
    }

    @PostMapping("/{provider}")
    Map<String, Object> webhook(
            @PathVariable String provider,
            @RequestHeader(value = "X-Billing-Signature", required = false) String signature,
            @RequestBody byte[] rawBody
    ) {
        return billingService.processWebhook(provider, rawBody, signature);
    }
}
