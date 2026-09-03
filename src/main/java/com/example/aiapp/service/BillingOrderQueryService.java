package com.example.aiapp.service;

import java.util.List;
import java.util.Map;

/**
 * 计费订单查询服务。
 */
public interface BillingOrderQueryService {

    List<Map<String, Object>> listOrders(long userId, int limit);

    Map<String, Object> getOrder(long userId, String orderNo);

    List<Map<String, Object>> listRefunds(long userId);
}
