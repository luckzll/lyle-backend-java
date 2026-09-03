package com.example.aiapp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.aiapp.domain.entity.BillingPriceVersionEntity;
import com.example.aiapp.domain.vo.BillingCatalogRowVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 商品价格版本数据访问接口。
 */
@Mapper
public interface BillingPriceVersionMapper extends BaseMapper<BillingPriceVersionEntity> {

    /**
     * 查询当前可售的已发布价格目录。
     *
     * @return 前端可展示的价格目录
     */
    List<BillingCatalogRowVO> selectPublishedCatalog();

    /**
     * 按价格版本编码查询价格。
     *
     * @param versionCode 价格版本编码
     * @return 价格版本，不存在时返回 {@code null}
     */
    BillingPriceVersionEntity selectByVersionCode(@Param("versionCode") String versionCode);

    /**
     * 以草稿状态为并发条件发布价格版本。
     *
     * @param id 价格版本主键
     * @param publishedAt 发布时间
     * @return 受影响行数
     */
    int publishDraft(@Param("id") Long id, @Param("publishedAt") LocalDateTime publishedAt);
}
