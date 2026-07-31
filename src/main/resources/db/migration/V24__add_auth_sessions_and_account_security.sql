ALTER TABLE app_user
  ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  ADD COLUMN auth_version INT NOT NULL DEFAULT 1,
  ADD COLUMN password_changed_at DATETIME NULL,
  ADD COLUMN last_login_at DATETIME NULL,
  ADD COLUMN updated_at DATETIME NULL;

CREATE TABLE auth_session (
  id BIGINT NOT NULL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  token_family_id VARCHAR(64) NOT NULL,
  auth_version INT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  last_used_at DATETIME(6) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  revoked_at DATETIME(6) NULL,
  revoke_reason VARCHAR(64) NULL,
  user_agent_hash CHAR(64) NULL,
  ip_prefix_hash CHAR(64) NULL,
  KEY idx_auth_session_user_active (user_id, revoked_at, expires_at),
  KEY idx_auth_session_family (token_family_id)
);

CREATE TABLE auth_refresh_token (
  id BIGINT NOT NULL PRIMARY KEY,
  session_id BIGINT NOT NULL,
  refresh_token_hash CHAR(64) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  used_at DATETIME(6) NULL,
  revoked_at DATETIME(6) NULL,
  replaced_by_token_id BIGINT NULL,
  UNIQUE KEY uk_auth_refresh_token_hash (refresh_token_hash),
  KEY idx_auth_refresh_token_session (session_id, created_at)
);

CREATE TABLE auth_security_event (
  id BIGINT NOT NULL PRIMARY KEY,
  user_id BIGINT NULL,
  event_type VARCHAR(64) NOT NULL,
  outcome VARCHAR(32) NOT NULL,
  reason_code VARCHAR(64) NULL,
  username_hash CHAR(64) NULL,
  ip_hash CHAR(64) NULL,
  created_at DATETIME(6) NOT NULL,
  KEY idx_auth_security_event_created (created_at),
  KEY idx_auth_security_event_user_created (user_id, created_at)
);
