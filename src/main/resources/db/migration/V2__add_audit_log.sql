CREATE TABLE IF NOT EXISTS audit_log (
  id BIGINT NOT NULL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  action VARCHAR(64) NOT NULL,
  target VARCHAR(64) NOT NULL,
  target_id VARCHAR(128) NOT NULL,
  created_at DATETIME NOT NULL,
  KEY idx_audit_log_user_created (user_id, created_at)
);
