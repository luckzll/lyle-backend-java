INSERT INTO ai_config (config_key, config_value, description, updated_at)
VALUES
  ('TOOL_ROUTER_LLM_ENABLED', 'true', '是否启用 LLM Tool Router，用于智能判断工具调用', NOW()),
  ('TOOL_ROUTER_CONTEXT_MAX_CHARS', '3000', 'LLM Tool Router 可读取的上下文最大字符数', NOW())
ON DUPLICATE KEY UPDATE config_key = config_key;
