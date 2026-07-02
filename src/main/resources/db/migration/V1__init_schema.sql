CREATE TABLE IF NOT EXISTS app_user (
  id BIGINT NOT NULL PRIMARY KEY,
  username VARCHAR(128) NOT NULL,
  role VARCHAR(32) NOT NULL DEFAULT 'USER',
  password_hash VARCHAR(255) NOT NULL,
  created_at DATETIME NOT NULL,
  UNIQUE KEY uk_app_user_username (username)
);

CREATE TABLE IF NOT EXISTS conversation (
  id BIGINT NOT NULL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  title VARCHAR(255) NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  KEY idx_conversation_user_updated (user_id, updated_at)
);

CREATE TABLE IF NOT EXISTS chat_message (
  id BIGINT NOT NULL PRIMARY KEY,
  conversation_id VARCHAR(128) NOT NULL,
  role VARCHAR(32) NOT NULL,
  content TEXT NOT NULL,
  created_at DATETIME NOT NULL,
  KEY idx_chat_message_conversation_created (conversation_id, created_at)
);

CREATE TABLE IF NOT EXISTS knowledge_base (
  id BIGINT NOT NULL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  name VARCHAR(255) NOT NULL,
  created_at DATETIME NOT NULL,
  KEY idx_knowledge_base_user_created (user_id, created_at)
);

CREATE TABLE IF NOT EXISTS knowledge_file (
  id BIGINT NOT NULL PRIMARY KEY,
  knowledge_base_id BIGINT NOT NULL,
  file_name VARCHAR(255) NOT NULL,
  storage_path VARCHAR(1024) NOT NULL,
  parse_status VARCHAR(32) NOT NULL,
  created_at DATETIME NOT NULL,
  KEY idx_knowledge_file_kb_created (knowledge_base_id, created_at)
);

CREATE TABLE IF NOT EXISTS audit_log (
  id BIGINT NOT NULL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  action VARCHAR(64) NOT NULL,
  target VARCHAR(64) NOT NULL,
  target_id VARCHAR(128) NOT NULL,
  created_at DATETIME NOT NULL,
  KEY idx_audit_log_user_created (user_id, created_at)
);
