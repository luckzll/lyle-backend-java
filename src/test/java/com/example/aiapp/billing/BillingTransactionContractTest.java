package com.example.aiapp.billing;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

class BillingTransactionContractTest {

    @Test
    void firstAccountSnapshotCannotUseMySqlRepeatableRead() throws NoSuchMethodException {
        assertReadCommitted("account", long.class);
        assertReadCommitted("ledger", long.class, String.class, int.class);
    }

    private static void assertReadCommitted(String methodName, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Transactional transaction = BillingService.class
                .getMethod(methodName, parameterTypes)
                .getAnnotation(Transactional.class);

        assertThat(transaction)
                .as("%s must declare transaction isolation", methodName)
                .isNotNull();
        assertThat(transaction.isolation())
                .as("%s must see a welcome grant committed by a concurrent initializer", methodName)
                .isEqualTo(Isolation.READ_COMMITTED);
    }
}
