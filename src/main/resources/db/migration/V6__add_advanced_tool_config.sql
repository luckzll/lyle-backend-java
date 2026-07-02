INSERT INTO ai_config (config_key, config_value, description, updated_at)
VALUES
  ('TOOL_WEB_SEARCH_TOP_K', '5', '网页搜索返回结果数量', NOW()),
  ('TOOL_FILE_PARSE_ENABLED', 'true', '是否开启文件解析工具', NOW()),
  ('TOOL_FILE_PARSE_MAX_CHARS', '4000', '文件解析工具注入上下文的最大字符数', NOW()),
  ('TOOL_TODO_ENABLED', 'true', '是否开启待办工具', NOW()),
  ('TOOL_CALENDAR_ENABLED', 'true', '是否开启日历工具', NOW())
ON DUPLICATE KEY UPDATE config_key = config_key;
