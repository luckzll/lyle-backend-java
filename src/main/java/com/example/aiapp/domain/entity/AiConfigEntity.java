package com.example.aiapp.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统 AI 配置实体，对应 ai_config 表。
 */
@Data
@TableName("ai_config")
public class AiConfigEntity {

    @TableId("config_key")
    private String configKey;

    @TableField("config_value")
    private String configValue;

    private String description;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
