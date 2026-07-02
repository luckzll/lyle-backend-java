UPDATE ai_config
SET config_value = CONCAT(config_value, ',weather'),
    updated_at = NOW()
WHERE config_key = 'TOOL_ALLOWED_NAMES'
  AND config_value NOT LIKE '%weather%';

INSERT INTO ai_config (config_key, config_value, description, updated_at)
VALUES ('TOOL_WEATHER_ENABLED', 'true', '是否开启天气查询工具', NOW())
ON DUPLICATE KEY UPDATE config_key = config_key;
