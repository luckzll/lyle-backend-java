package com.example.aiapp.service.impl;

import com.example.aiapp.billing.BillingRequests;
import com.example.aiapp.domain.dto.BillingCatalogItemDTO;
import com.example.aiapp.domain.entity.BillingPriceVersionEntity;
import com.example.aiapp.domain.entity.BillingProductEntity;
import com.example.aiapp.domain.vo.BillingCatalogRowVO;
import com.example.aiapp.mapper.BillingPriceVersionMapper;
import com.example.aiapp.mapper.BillingProductMapper;
import com.example.aiapp.service.BillingCatalogService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 计费商品与价格目录服务实现。
 */
@Service
public class BillingCatalogServiceImpl implements BillingCatalogService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final BillingProductMapper productMapper;
    private final BillingPriceVersionMapper priceVersionMapper;
    private final ObjectMapper objectMapper;

    public BillingCatalogServiceImpl(
            BillingProductMapper productMapper,
            BillingPriceVersionMapper priceVersionMapper,
            ObjectMapper objectMapper
    ) {
        this.productMapper = productMapper;
        this.priceVersionMapper = priceVersionMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<BillingCatalogItemDTO> listPublishedCatalog() {
        return priceVersionMapper.selectPublishedCatalog().stream()
                .map(this::catalogItem)
                .toList();
    }

    @Override
    @Transactional
    public Map<String, Object> createProduct(BillingRequests.CreateProduct request) {
        String productCode = normalizeCode(request.productCode(), 64);
        BillingProductEntity existing = findProduct(productCode, false);
        if (existing != null) {
            return productMap(existing);
        }

        LocalDateTime now = LocalDateTime.now();
        BillingProductEntity product = new BillingProductEntity();
        product.setProductCode(productCode);
        product.setProductType(allowedProductType(request.productType()));
        product.setDisplayName(safe(request.displayName(), 128));
        product.setDescription(safeNullable(request.description(), 512));
        product.setStatus("ACTIVE");
        product.setCreatedAt(now);
        product.setUpdatedAt(now);
        productMapper.insert(product);
        return productMap(product);
    }

    @Override
    @Transactional
    public Map<String, Object> createPrice(BillingRequests.CreatePrice request) {
        String versionCode = normalizeCode(request.versionCode(), 64);
        BillingPriceVersionEntity existing = findPrice(versionCode);
        if (existing != null) {
            return priceMap(existing);
        }

        BillingProductEntity product = findProduct(normalizeCode(request.productCode(), 64), true);
        if (product == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (request.validityDays() != null && request.validityDays() <= 0) {
            throw badRequest("有效期天数必须大于 0。");
        }
        if (request.sellFrom() != null
                && request.sellUntil() != null
                && !request.sellUntil().isAfter(request.sellFrom())) {
            throw badRequest("停售时间必须晚于开售时间。");
        }

        Map<String, Object> entitlements = new LinkedHashMap<>();
        entitlements.put("benefits", request.benefits() == null ? List.of() : request.benefits());
        entitlements.put("includedCreditMicros", request.includedCreditMicros());

        BillingPriceVersionEntity price = new BillingPriceVersionEntity();
        price.setProductId(product.getId());
        price.setVersionCode(versionCode);
        price.setCurrency(normalizeCurrency(request.currency()));
        price.setAmountMinor(request.amountMinor());
        price.setIncludedCreditMicros(request.includedCreditMicros());
        price.setValidityDays(request.validityDays());
        price.setEntitlementSnapshot(json(entitlements));
        price.setPricingRuleSnapshot(json(
                request.pricingRules() == null ? Map.of() : request.pricingRules()
        ));
        price.setStatus("DRAFT");
        price.setSellFrom(request.sellFrom());
        price.setSellUntil(request.sellUntil());
        price.setCreatedAt(LocalDateTime.now());
        priceVersionMapper.insert(price);
        return priceMap(price);
    }

    @Override
    @Transactional
    public Map<String, Object> publishPrice(String versionCode) {
        String code = normalizeCode(versionCode, 64);
        BillingPriceVersionEntity price = findPrice(code);
        if (price == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if ("PUBLISHED".equals(normalize(price.getStatus()))) {
            return priceMap(price);
        }
        if (!"DRAFT".equals(normalize(price.getStatus()))) {
            throw conflict("只有草稿价格可以发布。");
        }

        LocalDateTime publishedAt = LocalDateTime.now();
        int updated = priceVersionMapper.publishDraft(price.getId(), publishedAt);
        if (updated == 0) {
            BillingPriceVersionEntity concurrent = findPrice(code);
            if (concurrent != null && "PUBLISHED".equals(normalize(concurrent.getStatus()))) {
                return priceMap(concurrent);
            }
            throw conflict("价格状态已发生变化，请刷新后重试。");
        }
        price.setStatus("PUBLISHED");
        price.setPublishedAt(publishedAt);
        return priceMap(price);
    }

    private BillingProductEntity findProduct(String productCode, boolean activeOnly) {
        return productMapper.selectByProductCode(productCode, activeOnly);
    }

    private BillingPriceVersionEntity findPrice(String versionCode) {
        return priceVersionMapper.selectByVersionCode(versionCode);
    }

    private BillingCatalogItemDTO catalogItem(BillingCatalogRowVO row) {
        long amountMinor = row.getAmountMinor() == null ? 0L : row.getAmountMinor();
        long includedCreditMicros = row.getIncludedCreditMicros() == null
                ? 0L
                : row.getIncludedCreditMicros();
        return new BillingCatalogItemDTO(
                row.getProductCode(),
                row.getProductType(),
                row.getDisplayName(),
                row.getDescription(),
                row.getVersionCode(),
                amount(row.getCurrency(), amountMinor),
                includedCreditMicros,
                row.getValidityDays(),
                benefits(row.getEntitlementSnapshot()),
                row.getSellUntil()
        );
    }

    private Map<String, Object> amount(String currency, long payable) {
        String normalizedCurrency = normalizeCurrency(currency);
        BigDecimal value = BigDecimal.valueOf(payable, 2);
        String symbol = "CNY".equals(normalizedCurrency) ? "¥" : normalizedCurrency + " ";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("currency", normalizedCurrency);
        result.put("payableMinor", payable);
        result.put("paidMinor", 0L);
        result.put("formatted", symbol + value.setScale(2, RoundingMode.UNNECESSARY).toPlainString());
        return result;
    }

    private List<?> benefits(String entitlementSnapshot) {
        Object value = jsonMap(entitlementSnapshot).get("benefits");
        return value instanceof List<?> list ? list : List.of();
    }

    private Map<String, Object> jsonMap(String value) {
        if (!StringUtils.hasText(value)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize billing snapshot.", exception);
        }
    }

    private static Map<String, Object> productMap(BillingProductEntity product) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", product.getId());
        result.put("product_code", product.getProductCode());
        result.put("product_type", product.getProductType());
        result.put("display_name", product.getDisplayName());
        result.put("description", product.getDescription());
        result.put("status", product.getStatus());
        result.put("created_at", product.getCreatedAt());
        result.put("updated_at", product.getUpdatedAt());
        return result;
    }

    private static Map<String, Object> priceMap(BillingPriceVersionEntity price) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", price.getId());
        result.put("product_id", price.getProductId());
        result.put("version_code", price.getVersionCode());
        result.put("currency", price.getCurrency());
        result.put("amount_minor", price.getAmountMinor());
        result.put("included_credit_micros", price.getIncludedCreditMicros());
        result.put("validity_days", price.getValidityDays());
        result.put("entitlement_snapshot", price.getEntitlementSnapshot());
        result.put("pricing_rule_snapshot", price.getPricingRuleSnapshot());
        result.put("status", price.getStatus());
        result.put("sell_from", price.getSellFrom());
        result.put("sell_until", price.getSellUntil());
        result.put("created_at", price.getCreatedAt());
        result.put("published_at", price.getPublishedAt());
        return result;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeCode(String value, int maxLength) {
        String result = normalize(value);
        if (!result.matches("[A-Z0-9][A-Z0-9._:-]*") || result.length() > maxLength) {
            throw badRequest("业务编码格式无效。");
        }
        return result;
    }

    private static String normalizeCurrency(String value) {
        String result = normalize(value);
        if (!result.matches("[A-Z]{3}")) {
            throw badRequest("币种代码无效。");
        }
        return result;
    }

    private static String allowedProductType(String value) {
        String result = normalize(value);
        if (!List.of("PLAN", "CREDIT_PACK").contains(result)) {
            throw badRequest("商品类型仅支持 PLAN 或 CREDIT_PACK。");
        }
        return result;
    }

    private static String safe(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            throw badRequest("必填字段不能为空。");
        }
        String result = value.trim();
        if (result.length() > maxLength) {
            throw badRequest("字段长度超出限制。");
        }
        return result;
    }

    private static String safeNullable(String value, int maxLength) {
        return StringUtils.hasText(value) ? safe(value, maxLength) : null;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
