ALTER TABLE billing_order
  ADD COLUMN user_hidden_at DATETIME(6) NULL AFTER closed_at,
  ADD KEY idx_billing_order_user_visible_time (user_id, user_hidden_at, created_at);
