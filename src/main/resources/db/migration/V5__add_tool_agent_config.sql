INSERT INTO ai_config (config_key, config_value, description, updated_at)
VALUES
  ('TOOL_ALLOWED_NAMES', 'health_check,memory_snapshot,list_tools,memory_stats,web_search,file_parse,todo,calendar', 'Tool Agent 工具白名单，逗号分隔', NOW()),
  ('TOOL_AUDIT_ENABLED', 'true', '是否开启工具调用审计', NOW()),
  ('TOOL_DATABASE_QUERY_ENABLED', 'true', '是否开启只读数据库统计工具', NOW()),
  ('TOOL_WEB_SEARCH_ENABLED', 'false', '是否开启网页搜索工具', NOW()),
  ('TOOL_WEB_SEARCH_PROVIDER', '', '网页搜索 provider，如 Tavily、SerpAPI、Bing', NOW())
ON DUPLICATE KEY UPDATE config_key = config_key;
