package com.example.aiapp.billing.payment;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AlipayPaymentGatewayTest {

    @Test
    void formatsUtcExpiryInAlipayBusinessTimezone() {
        LocalDateTime expiresAtUtc = LocalDateTime.of(2026, 9, 2, 18, 19, 51);

        assertEquals(
                "2026-09-03 02:19:51",
                AlipayPaymentGateway.formatAlipayTime(expiresAtUtc)
        );
    }

    @Test
    void treatsMissingProviderTradeAsSafelyClosed() {
        assertEquals("CLOSED", AlipayPaymentGateway.closeStatus(false, "ACQ.TRADE_NOT_EXIST"));
        assertEquals("CLOSED", AlipayPaymentGateway.closeStatus(true, null));
        assertEquals("UNKNOWN", AlipayPaymentGateway.closeStatus(false, "SYSTEM_ERROR"));
    }
}
