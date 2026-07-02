UPDATE ai_config
SET config_value = CONCAT(config_value, ',image_edit')
WHERE config_key = 'TOOL_ALLOWED_NAMES'
  AND config_value NOT LIKE '%image_edit%';

INSERT INTO ai_config (config_key, config_value, description, updated_at)
VALUES
  ('TOOL_IMAGE_EDIT_ENABLED', 'true', '是否开启图片编辑/图生图工具', NOW())
ON DUPLICATE KEY UPDATE config_key = config_key;
