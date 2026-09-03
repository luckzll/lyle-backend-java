package com.example.aiapp.billing.payment;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class MockPaymentGateway implements PaymentGateway {

    @Override
    public String provider() {
        return "MOCK";
    }

    @Override
    public CheckoutResult createPayment(CreatePaymentCommand command) {
        return new CheckoutResult(
                provider(),
                "PENDING",
                "SANDBOX",
                null,
                command.expiresAt()
        );
    }

    @Override
    public PaymentResult queryPayment(String paymentNo) {
        return new PaymentResult("PENDING", null);
    }

    @Override
    public PaymentResult closePayment(String paymentNo) {
        return new PaymentResult("CLOSED", null);
    }

    @Override
    public RefundResult createRefund(CreateRefundCommand command) {
        return new RefundResult(
                "SUCCEEDED",
                "mock-refund-" + UUID.randomUUID()
        );
    }

    @Override
    public RefundResult queryRefund(String paymentNo, String refundNo) {
        return new RefundResult("SUCCEEDED", "mock-refund-" + refundNo);
    }
}
