ALTER TABLE admin_credit_operation
  ADD COLUMN operation_type VARCHAR(16) NOT NULL DEFAULT 'GRANT' AFTER user_id;
