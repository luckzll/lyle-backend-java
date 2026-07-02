INSERT INTO ai_config (config_key, config_value, description, updated_at)
VALUES ('EVALUATION_JUDGE_ENABLED', 'false', '是否开启评测 LLM Judge 裁判模型', NOW())
ON DUPLICATE KEY UPDATE config_key = config_key;
