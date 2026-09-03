package com.example.aiapp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.aiapp.domain.entity.BillingProductEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 计费商品数据访问接口。
 */
@Mapper
public interface BillingProductMapper extends BaseMapper<BillingProductEntity> {

    /**
     * 按商品编码查询商品。
     *
     * @param productCode 商品编码
     * @param activeOnly 是否仅查询启用商品
     * @return 商品，不存在时返回 {@code null}
     */
    BillingProductEntity selectByProductCode(
            @Param("productCode") String productCode,
            @Param("activeOnly") boolean activeOnly
    );
}
