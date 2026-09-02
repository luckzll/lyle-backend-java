package com.example.aiapp.billing.support;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Billing-wide identifiers. A single sequence is shared by all billing modules so
 * splitting the original service cannot introduce duplicate numeric identifiers.
 */
public final class BillingIdentifiers {

    private static final DateTimeFormatter BUSINESS_NO_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final AtomicLong ID_SEQUENCE =
            new AtomicLong(System.currentTimeMillis() * 1_000L);

    private BillingIdentifiers() {
    }

    public static long nextId() {
        return ID_SEQUENCE.incrementAndGet();
    }

    public static String businessNo(String prefix) {
        return prefix
                + LocalDateTime.now(ZoneOffset.UTC).format(BUSINESS_NO_TIME)
                + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 12)
                .toUpperCase(Locale.ROOT);
    }
}
