CREATE TABLE admin_credit_operation (
  request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  actor_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  credit_micros BIGINT NOT NULL,
  validity_days INT NULL,
  reason VARCHAR(512) NOT NULL,
  status VARCHAR(16) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  KEY idx_admin_credit_user (user_id, created_at)
);
