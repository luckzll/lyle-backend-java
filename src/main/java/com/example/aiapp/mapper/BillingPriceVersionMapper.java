package com.example.aiapp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.aiapp.domain.entity.BillingPriceVersionEntity;
import com.example.aiapp.domain.vo.BillingCatalogRowVO;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface BillingPriceVersionMapper extends BaseMapper<BillingPriceVersionEntity> {

    @Select("""
            SELECT p.product_code, p.product_type, p.display_name, p.description,
                   v.version_code, v.currency, v.amount_minor, v.included_credit_micros,
                   v.validity_days, v.entitlement_snapshot, v.sell_until
            FROM billing_product p
            JOIN billing_price_version v ON v.product_id = p.id
            WHERE p.status = 'ACTIVE'
              AND v.status = 'PUBLISHED'
              AND (v.sell_from IS NULL OR v.sell_from <= CURRENT_TIMESTAMP(6))
              AND (v.sell_until IS NULL OR v.sell_until > CURRENT_TIMESTAMP(6))
            ORDER BY CASE p.product_type
                       WHEN 'PLAN' THEN 1
                       WHEN 'CREDIT_PACK' THEN 2
                       ELSE 3
                     END,
                     v.amount_minor
            """)
    List<BillingCatalogRowVO> selectPublishedCatalog();
}
