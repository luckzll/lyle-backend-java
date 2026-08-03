package com.example.aiapp.billing.payment;

import java.time.LocalDateTime;

public interface PaymentGateway {

    String provider();

    CheckoutResult createPayment(CreatePaymentCommand command);

    PaymentResult queryPayment(String paymentNo);

    PaymentResult closePayment(String paymentNo);

    RefundResult createRefund(CreateRefundCommand command);

    record CreatePaymentCommand(
            String paymentNo,
            String orderNo,
            long amountMinor,
            String currency,
            String description,
            LocalDateTime expiresAt
    ) {
    }

    record CheckoutResult(
            String provider,
            String status,
            String checkoutType,
            String action,
            LocalDateTime expiresAt
    ) {
    }

    record PaymentResult(
            String status,
            String providerTransactionId
    ) {
    }

    record CreateRefundCommand(
            String refundNo,
            String paymentNo,
            long amountMinor,
            String currency,
            String reason
    ) {
    }

    record RefundResult(
            String status,
            String providerRefundId
    ) {
    }
}
