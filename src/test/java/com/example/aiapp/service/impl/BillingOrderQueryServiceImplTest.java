package com.example.aiapp.service.impl;

import com.example.aiapp.mapper.BillingOrderMapper;
import com.example.aiapp.mapper.RefundOrderMapper;
import com.example.aiapp.domain.vo.BillingOrderReadVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BillingOrderQueryServiceImplTest {

    @Test
    void orderReadModelKeepsTheExistingFrontendContractAndBoundsTheLimit() {
        BillingOrderMapper orderMapper = mock(BillingOrderMapper.class);
        RefundOrderMapper refundMapper = mock(RefundOrderMapper.class);
        BillingOrderQueryServiceImpl service = new BillingOrderQueryServiceImpl(
                orderMapper,
                refundMapper,
                new ObjectMapper()
        );

        LocalDateTime createdAt = LocalDateTime.of(2026, 9, 3, 12, 0);
        BillingOrderReadVO row = new BillingOrderReadVO();
        row.setOrderNo("L-100");
        row.setStatus("PENDING_PAYMENT");
        row.setOrderType("NEW_PURCHASE");
        row.setProductCode("PRO");
        row.setPriceVersionCode("PRO-CNY-1");
        row.setProductSnapshot("{\"name\":\"专业版\"}");
        row.setCurrency("CNY");
        row.setPayableAmountMinor(1999L);
        row.setPaidAmountMinor(0L);
        row.setPaymentNo("P-100");
        row.setPaymentStatus("PENDING");
        row.setPaymentProvider("ALIPAY");
        row.setExpiresAt(createdAt.plusMinutes(30));
        row.setCreatedAt(createdAt);
        when(orderMapper.selectUserOrders(7L, 100)).thenReturn(List.of(row));

        Map<String, Object> response = service.listOrders(7L, 999).get(0);

        assertThat(response.keySet()).containsExactly(
                "orderNo",
                "status",
                "orderType",
                "productCode",
                "priceVersionCode",
                "product",
                "amount",
                "paymentNo",
                "paymentStatus",
                "paymentProvider",
                "refundStatus",
                "refundSucceededAt",
                "expiresAt",
                "paidAt",
                "fulfilledAt",
                "createdAt"
        );
        assertThat(response)
                .containsEntry("orderNo", "L-100")
                .containsEntry("paymentProvider", "ALIPAY")
                .containsEntry("createdAt", createdAt);
        @SuppressWarnings("unchecked")
        Map<String, Object> product = (Map<String, Object>) response.get("product");
        @SuppressWarnings("unchecked")
        Map<String, Object> amount = (Map<String, Object>) response.get("amount");
        assertThat(product).containsEntry("name", "专业版");
        assertThat(amount)
                .containsEntry("payableMinor", 1999L)
                .containsEntry("formatted", "¥19.99");
    }
}
