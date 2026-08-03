package com.example.aiapp.billing;

import com.example.aiapp.utils.CurrentUserUtils;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/billing")
public class BillingController {

    private final BillingService billingService;

    public BillingController(BillingService billingService) {
        this.billingService = billingService;
    }

    @GetMapping("/catalog")
    List<Map<String, Object>> catalog() {
        return billingService.catalog();
    }

    @GetMapping("/account")
    Map<String, Object> account() {
        return billingService.account(CurrentUserUtils.currentUserId());
    }

    @GetMapping("/account/ledger")
    List<Map<String, Object>> ledger(
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return billingService.ledger(CurrentUserUtils.currentUserId(), type, limit);
    }

    @GetMapping("/orders")
    List<Map<String, Object>> orders(@RequestParam(defaultValue = "50") int limit) {
        return billingService.orders(CurrentUserUtils.currentUserId(), limit);
    }

    @GetMapping("/orders/{orderNo}")
    Map<String, Object> order(@PathVariable String orderNo) {
        return billingService.order(CurrentUserUtils.currentUserId(), orderNo);
    }

    @PostMapping("/orders")
    Map<String, Object> createOrder(@Valid @RequestBody BillingRequests.CreateOrder request) {
        return billingService.createOrder(CurrentUserUtils.currentUserId(), request);
    }

    @PostMapping("/orders/{orderNo}/checkout")
    Map<String, Object> checkout(@PathVariable String orderNo) {
        return billingService.checkout(CurrentUserUtils.currentUserId(), orderNo);
    }

    @GetMapping("/orders/{orderNo}/status")
    Map<String, Object> orderStatus(@PathVariable String orderNo) {
        return billingService.order(CurrentUserUtils.currentUserId(), orderNo);
    }

    @PostMapping("/orders/{orderNo}/close")
    Map<String, Object> close(@PathVariable String orderNo) {
        return billingService.closeOrder(CurrentUserUtils.currentUserId(), orderNo);
    }

    @GetMapping("/refunds")
    List<Map<String, Object>> refunds() {
        return billingService.refunds(CurrentUserUtils.currentUserId());
    }
}
