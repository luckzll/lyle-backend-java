UPDATE ai_config
SET config_value = CONCAT(config_value, ',image_analyze')
WHERE config_key = 'TOOL_ALLOWED_NAMES'
  AND config_value NOT LIKE '%image_analyze%';

UPDATE ai_config
SET config_value = CONCAT(config_value, ',image_generate')
WHERE config_key = 'TOOL_ALLOWED_NAMES'
  AND config_value NOT LIKE '%image_generate%';

INSERT INTO ai_config (config_key, config_value, description, updated_at)
VALUES
  ('TOOL_IMAGE_ANALYZE_ENABLED', 'true', '是否开启图片识别工具', NOW()),
  ('TOOL_IMAGE_GENERATE_ENABLED', 'true', '是否开启图片生成工具', NOW())
ON DUPLICATE KEY UPDATE config_key = config_key;
