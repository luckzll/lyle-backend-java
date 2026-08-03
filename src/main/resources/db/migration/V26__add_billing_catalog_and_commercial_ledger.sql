CREATE TABLE billing_product (
  id BIGINT NOT NULL PRIMARY KEY,
  product_code VARCHAR(64) NOT NULL,
  product_type VARCHAR(32) NOT NULL,
  display_name VARCHAR(128) NOT NULL,
  description VARCHAR(512) NULL,
  status VARCHAR(32) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_billing_product_code (product_code),
  KEY idx_billing_product_status (status, product_type)
);
CREATE TABLE billing_price_version (
  id BIGINT NOT NULL PRIMARY KEY,
  product_id BIGINT NOT NULL,
  version_code VARCHAR(64) NOT NULL,
  currency CHAR(3) NOT NULL,
  amount_minor BIGINT NOT NULL,
  included_credit_micros BIGINT NOT NULL,
  validity_days INT NULL,
  entitlement_snapshot JSON NOT NULL,
  pricing_rule_snapshot JSON NOT NULL,
  status VARCHAR(32) NOT NULL,
  sell_from DATETIME(6) NULL,
  sell_until DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  published_at DATETIME(6) NULL,
  UNIQUE KEY uk_billing_price_version_code (version_code),
  KEY idx_billing_price_product_status (product_id, status),
  CONSTRAINT fk_billing_price_product FOREIGN KEY (product_id) REFERENCES billing_product(id)
);

CREATE TABLE billing_usage_pricing_version (
  id BIGINT NOT NULL PRIMARY KEY,
  version_code VARCHAR(64) NOT NULL,
  rules_json JSON NOT NULL,
  status VARCHAR(32) NOT NULL,
  effective_from DATETIME(6) NOT NULL,
  effective_until DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  published_at DATETIME(6) NULL,
  UNIQUE KEY uk_billing_usage_pricing_version (version_code),
  KEY idx_billing_usage_pricing_active (status, effective_from, effective_until)
);

CREATE TABLE commercial_account (
  id BIGINT NOT NULL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_commercial_account_user (user_id)
);

CREATE TABLE entitlement_grant (
  id BIGINT NOT NULL PRIMARY KEY,
  account_id BIGINT NOT NULL,
  grant_type VARCHAR(32) NOT NULL,
  source_type VARCHAR(32) NOT NULL,
  source_id VARCHAR(128) NOT NULL,
  entitlement_code VARCHAR(64) NOT NULL,
  price_version_code VARCHAR(64) NULL,
  quantity_micros BIGINT NOT NULL,
  remaining_micros BIGINT NOT NULL,
  valid_from DATETIME(6) NOT NULL,
  expires_at DATETIME(6) NULL,
  status VARCHAR(32) NOT NULL,
  metadata_json JSON NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_entitlement_source_code (source_type, source_id, entitlement_code),
  KEY idx_entitlement_account_expiry (account_id, status, expires_at),
  CONSTRAINT fk_entitlement_account FOREIGN KEY (account_id) REFERENCES commercial_account(id)
);

CREATE TABLE commercial_ledger_entry (
  id BIGINT NOT NULL PRIMARY KEY,
  account_id BIGINT NOT NULL,
  grant_id BIGINT NULL,
  entry_type VARCHAR(32) NOT NULL,
  direction VARCHAR(8) NOT NULL,
  credit_micros BIGINT NOT NULL,
  balance_after_micros BIGINT NOT NULL,
  source_type VARCHAR(32) NOT NULL,
  source_id VARCHAR(128) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  occurred_at DATETIME(6) NOT NULL,
  expires_at DATETIME(6) NULL,
  metadata_json JSON NULL,
  created_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_commercial_ledger_idempotency (idempotency_key),
  KEY idx_commercial_ledger_account_time (account_id, occurred_at),
  CONSTRAINT fk_commercial_ledger_account FOREIGN KEY (account_id) REFERENCES commercial_account(id),
  CONSTRAINT fk_commercial_ledger_grant FOREIGN KEY (grant_id) REFERENCES entitlement_grant(id)
);

CREATE TABLE commercial_reservation (
  id VARCHAR(64) NOT NULL PRIMARY KEY,
  account_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  turn_id VARCHAR(128) NULL,
  reserved_credit_micros BIGINT NOT NULL,
  consumed_credit_micros BIGINT NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  KEY idx_commercial_reservation_account_status (account_id, status, expires_at),
  KEY idx_commercial_reservation_user_turn (user_id, turn_id),
  CONSTRAINT fk_commercial_reservation_account FOREIGN KEY (account_id) REFERENCES commercial_account(id)
);

CREATE TABLE billable_usage (
  id BIGINT NOT NULL PRIMARY KEY,
  usage_event_id VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  account_id BIGINT NOT NULL,
  pricing_version_code VARCHAR(64) NOT NULL,
  charge_credit_micros BIGINT NOT NULL,
  usage_source VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  reservation_id VARCHAR(64) NULL,
  ledger_entry_id BIGINT NULL,
  metadata_json JSON NULL,
  created_at DATETIME(6) NOT NULL,
  settled_at DATETIME(6) NULL,
  UNIQUE KEY uk_billable_usage_event (usage_event_id),
  KEY idx_billable_usage_account_status (account_id, status),
  CONSTRAINT fk_billable_usage_account FOREIGN KEY (account_id) REFERENCES commercial_account(id),
  CONSTRAINT fk_billable_usage_ledger FOREIGN KEY (ledger_entry_id) REFERENCES commercial_ledger_entry(id)
);
