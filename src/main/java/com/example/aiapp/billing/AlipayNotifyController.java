package com.example.aiapp.billing;

import com.example.aiapp.billing.payment.AlipayPaymentGateway;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class AlipayNotifyController {

    private static final Logger log = LoggerFactory.getLogger(AlipayNotifyController.class);
    private final AlipayPaymentGateway paymentGateway;
    private final BillingService billingService;

    public AlipayNotifyController(
            AlipayPaymentGateway paymentGateway,
            BillingService billingService
    ) {
        this.paymentGateway = paymentGateway;
        this.billingService = billingService;
    }

    @PostMapping(
            value = "/billing/webhooks/alipay/notify",
            consumes = "application/x-www-form-urlencoded",
            produces = "text/plain;charset=UTF-8"
    )
    public String notify(HttpServletRequest request) {
        Map<String, String> parameters = collectFormParameters(request);
        try {
            if (!paymentGateway.verifyNotification(parameters)
                    || !paymentGateway.notificationOwnerMatches(parameters)) {
                log.warn("Rejected Alipay notification after signature or merchant validation failure.");
                return "fail";
            }
            return billingService.processAlipayNotification(parameters) ? "success" : "fail";
        } catch (RuntimeException exception) {
            log.warn("Failed to process a verified Alipay notification.");
            return "fail";
        }
    }

    private static Map<String, String> collectFormParameters(HttpServletRequest request) {
        Map<String, String> parameters = new HashMap<>();
        request.getParameterMap().forEach((name, values) ->
                parameters.put(name, String.join(",", values))
        );
        return parameters;
    }
}
