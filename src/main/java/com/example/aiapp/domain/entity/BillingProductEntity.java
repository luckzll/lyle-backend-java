package com.example.aiapp.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 计费商品实体，对应 billing_product 表。
 */
@Data
@TableName("billing_product")
public class BillingProductEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("product_code")
    private String productCode;

    @TableField("product_type")
    private String productType;

    @TableField("display_name")
    private String displayName;

    private String description;

    private String status;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
