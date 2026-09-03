package com.example.aiapp.service;

import com.example.aiapp.billing.BillingRequests;
import com.example.aiapp.domain.dto.BillingCatalogItemDTO;

import java.util.List;
import java.util.Map;

/**
 * 计费商品与价格目录服务。
 */
public interface BillingCatalogService {

    List<BillingCatalogItemDTO> listPublishedCatalog();

    Map<String, Object> createProduct(BillingRequests.CreateProduct request);

    Map<String, Object> createPrice(BillingRequests.CreatePrice request);

    Map<String, Object> publishPrice(String versionCode);
}
