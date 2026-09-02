package com.example.aiapp.domain.dto;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record BillingCatalogItemDTO(
        String productCode,
        String productType,
        String name,
        String description,
        String priceVersionCode,
        Map<String, Object> amount,
        long includedCreditMicros,
        Integer validityDays,
        List<?> benefits,
        LocalDateTime sellUntil
) {

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("productCode", productCode);
        result.put("productType", productType);
        result.put("name", name);
        result.put("description", description);
        result.put("priceVersionCode", priceVersionCode);
        result.put("amount", amount);
        result.put("includedCreditMicros", includedCreditMicros);
        result.put("validityDays", validityDays);
        result.put("benefits", benefits);
        result.put("sellUntil", sellUntil);
        return result;
    }
}
