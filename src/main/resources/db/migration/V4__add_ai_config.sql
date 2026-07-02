CREATE TABLE IF NOT EXISTS ai_config (
  config_key VARCHAR(128) NOT NULL PRIMARY KEY,
  config_value VARCHAR(512) NOT NULL,
  description VARCHAR(512) NOT NULL,
  updated_at DATETIME NOT NULL
);

INSERT INTO ai_config (config_key, config_value, description, updated_at)
VALUES
  ('REVIEWER_ENABLED', 'false', '是否开启 Reviewer Agent 审核', NOW()),
  ('REVIEWER_MAX_RETRY', '1', 'Reviewer 不通过时最多重写次数', NOW()),
  ('AGENT_GRAPH_MODE', 'multi', 'Agent 图模式：single 或 multi', NOW()),
  ('TOOL_AGENT_ENABLED', 'true', '是否开启 Tool Agent', NOW()),
  ('RERANK_ENABLED', 'true', '是否开启 RAG 和记忆召回重排', NOW()),
  ('RERANK_PROVIDER', 'lexical', 'Reranker 类型：lexical 或 flag_embedding', NOW()),
  ('RAG_QUERY_REWRITE_ENABLED', 'true', '是否开启 RAG query rewrite', NOW()),
  ('RAG_MULTI_QUERY_COUNT', '3', 'RAG 多 query 数量', NOW()),
  ('RAG_SUFFICIENCY_CHECK_ENABLED', 'true', '是否开启 RAG 资料充分性判断', NOW())
ON DUPLICATE KEY UPDATE config_key = config_key;
