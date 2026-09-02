package com.example.aiapp.service.impl;

import com.example.aiapp.domain.dto.BillingCatalogItemDTO;
import com.example.aiapp.domain.vo.BillingCatalogRowVO;
import com.example.aiapp.mapper.BillingPriceVersionMapper;
import com.example.aiapp.mapper.BillingProductMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BillingCatalogServiceImplTest {

    @Test
    void publishedCatalogKeepsTheExistingFrontendContract() {
        BillingProductMapper productMapper = mock(BillingProductMapper.class);
        BillingPriceVersionMapper priceMapper = mock(BillingPriceVersionMapper.class);
        BillingCatalogServiceImpl service = new BillingCatalogServiceImpl(
                productMapper,
                priceMapper,
                new ObjectMapper()
        );

        LocalDateTime sellUntil = LocalDateTime.of(2030, 1, 1, 0, 0);
        BillingCatalogRowVO row = new BillingCatalogRowVO();
        row.setProductCode("PRO");
        row.setProductType("PLAN");
        row.setDisplayName("专业版");
        row.setDescription("专业套餐");
        row.setVersionCode("PRO-CNY-1");
        row.setCurrency("CNY");
        row.setAmountMinor(1999L);
        row.setIncludedCreditMicros(10_000_000L);
        row.setValidityDays(30);
        row.setEntitlementSnapshot("{\"benefits\":[\"更多额度\"]}");
        row.setSellUntil(sellUntil);
        when(priceMapper.selectPublishedCatalog()).thenReturn(List.of(row));

        BillingCatalogItemDTO item = service.listPublishedCatalog().get(0);
        Map<String, Object> response = item.toMap();

        assertThat(response.keySet()).containsExactly(
                "productCode",
                "productType",
                "name",
                "description",
                "priceVersionCode",
                "amount",
                "includedCreditMicros",
                "validityDays",
                "benefits",
                "sellUntil"
        );
        assertThat(response)
                .containsEntry("productCode", "PRO")
                .containsEntry("priceVersionCode", "PRO-CNY-1")
                .containsEntry("benefits", List.of("更多额度"))
                .containsEntry("sellUntil", sellUntil);
        @SuppressWarnings("unchecked")
        Map<String, Object> amount = (Map<String, Object>) response.get("amount");
        assertThat(amount)
                .containsEntry("currency", "CNY")
                .containsEntry("payableMinor", 1999L)
                .containsEntry("paidMinor", 0L)
                .containsEntry("formatted", "¥19.99");
    }
}
