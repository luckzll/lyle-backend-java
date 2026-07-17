CREATE TABLE agent_mission (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  project_id BIGINT NULL,
  title VARCHAR(255) NOT NULL,
  goal TEXT NOT NULL,
  completion_criteria JSON NOT NULL,
  constraints_json JSON NOT NULL,
  template_id VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  active_grant_version INT NULL,
  notification_policy JSON NOT NULL,
  quiet_hours JSON NOT NULL,
  single_run_budget JSON NOT NULL,
  period_budget JSON NOT NULL,
  period_type VARCHAR(16) NOT NULL,
  valid_from DATETIME(6) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  next_run_at DATETIME(6) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  archived_at DATETIME(6) NULL,
  INDEX idx_mission_user_status_updated (user_id, status, updated_at),
  INDEX idx_mission_status_next_run (status, next_run_at)
);

CREATE TABLE delegation_grant (
  id BIGINT PRIMARY KEY,
  mission_id BIGINT NOT NULL,
  version INT NOT NULL,
  status VARCHAR(32) NOT NULL,
  summary TEXT NOT NULL,
  rules JSON NOT NULL,
  budget_policy JSON NOT NULL,
  valid_from DATETIME(6) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  approved_by BIGINT NULL,
  approved_at DATETIME(6) NULL,
  content_hash VARCHAR(128) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  UNIQUE KEY uq_delegation_grant_version (mission_id, version),
  INDEX idx_delegation_grant_status (mission_id, status),
  CONSTRAINT fk_delegation_grant_mission FOREIGN KEY (mission_id) REFERENCES agent_mission(id)
);

CREATE TABLE mission_trigger (
  id BIGINT PRIMARY KEY,
  mission_id BIGINT NOT NULL,
  type VARCHAR(32) NOT NULL,
  config JSON NOT NULL,
  timezone VARCHAR(64) NOT NULL,
  misfire_policy VARCHAR(16) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  next_fire_at DATETIME(6) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  INDEX idx_mission_trigger_due (enabled, next_fire_at),
  CONSTRAINT fk_mission_trigger_mission FOREIGN KEY (mission_id) REFERENCES agent_mission(id)
);

CREATE TABLE mission_trigger_occurrence (
  id BIGINT PRIMARY KEY,
  mission_id BIGINT NOT NULL,
  trigger_id BIGINT NOT NULL,
  scheduled_for DATETIME(6) NOT NULL,
  trigger_payload_summary JSON NOT NULL,
  status VARCHAR(32) NOT NULL,
  agent_task_id BIGINT NULL,
  lease_owner VARCHAR(128) NULL,
  lease_expires_at DATETIME(6) NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  completed_at DATETIME(6) NULL,
  UNIQUE KEY uq_mission_trigger_schedule (mission_id, trigger_id, scheduled_for),
  UNIQUE KEY uq_mission_occurrence_idempotency (idempotency_key),
  INDEX idx_mission_occurrence_claim (status, lease_expires_at),
  INDEX idx_mission_occurrence_mission_created (mission_id, created_at),
  CONSTRAINT fk_mission_occurrence_mission FOREIGN KEY (mission_id) REFERENCES agent_mission(id),
  CONSTRAINT fk_mission_occurrence_trigger FOREIGN KEY (trigger_id) REFERENCES mission_trigger(id)
);

CREATE TABLE authorization_decision (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  mission_id BIGINT NOT NULL,
  agent_task_id BIGINT NOT NULL,
  step_run_id BIGINT NULL,
  grant_version INT NOT NULL,
  tool_ref VARCHAR(255) NOT NULL,
  operation_name VARCHAR(64) NOT NULL,
  resource_ref_hash VARCHAR(128) NOT NULL,
  risk_level VARCHAR(16) NOT NULL,
  reversibility VARCHAR(32) NOT NULL,
  decision VARCHAR(32) NOT NULL,
  reason_code VARCHAR(64) NOT NULL,
  matched_rule_id VARCHAR(128) NULL,
  created_at DATETIME(6) NOT NULL,
  INDEX idx_authorization_decision_mission_created (mission_id, created_at),
  INDEX idx_authorization_decision_task_created (agent_task_id, created_at),
  CONSTRAINT fk_authorization_decision_mission FOREIGN KEY (mission_id) REFERENCES agent_mission(id),
  CONSTRAINT fk_authorization_decision_task FOREIGN KEY (agent_task_id) REFERENCES agent_task(id)
);

CREATE TABLE mission_budget_ledger (
  id BIGINT PRIMARY KEY,
  mission_id BIGINT NOT NULL,
  agent_task_id BIGINT NOT NULL,
  period_start DATETIME(6) NOT NULL,
  period_end DATETIME(6) NOT NULL,
  steps INT NOT NULL DEFAULT 0,
  tool_calls INT NOT NULL DEFAULT 0,
  model_tokens BIGINT NOT NULL DEFAULT 0,
  cost_micros BIGINT NOT NULL DEFAULT 0,
  elapsed_millis BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  INDEX idx_mission_budget_period (mission_id, period_start, period_end),
  CONSTRAINT fk_mission_budget_mission FOREIGN KEY (mission_id) REFERENCES agent_mission(id),
  CONSTRAINT fk_mission_budget_task FOREIGN KEY (agent_task_id) REFERENCES agent_task(id)
);

CREATE TABLE mission_exception (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  mission_id BIGINT NOT NULL,
  agent_task_id BIGINT NULL,
  type VARCHAR(32) NOT NULL,
  severity VARCHAR(16) NOT NULL,
  dedupe_key VARCHAR(128) NOT NULL,
  title VARCHAR(255) NOT NULL,
  summary TEXT NOT NULL,
  evidence_refs JSON NOT NULL,
  available_actions JSON NOT NULL,
  status VARCHAR(32) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  resolved_at DATETIME(6) NULL,
  INDEX idx_mission_exception_user_status (user_id, status, created_at),
  INDEX idx_mission_exception_dedupe (mission_id, dedupe_key, status),
  CONSTRAINT fk_mission_exception_mission FOREIGN KEY (mission_id) REFERENCES agent_mission(id),
  CONSTRAINT fk_mission_exception_task FOREIGN KEY (agent_task_id) REFERENCES agent_task(id)
);

CREATE TABLE agent_compensation (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  mission_id BIGINT NOT NULL,
  agent_task_id BIGINT NOT NULL,
  step_run_id BIGINT NOT NULL,
  tool_ref VARCHAR(255) NOT NULL,
  operation_name VARCHAR(64) NOT NULL,
  resource_ref_hash VARCHAR(128) NOT NULL,
  precondition_snapshot JSON NOT NULL,
  compensation_payload_ref VARCHAR(512) NULL,
  expires_at DATETIME(6) NOT NULL,
  status VARCHAR(32) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  requested_at DATETIME(6) NULL,
  completed_at DATETIME(6) NULL,
  UNIQUE KEY uq_agent_compensation_idempotency (idempotency_key),
  INDEX idx_agent_compensation_step (step_run_id),
  CONSTRAINT fk_agent_compensation_mission FOREIGN KEY (mission_id) REFERENCES agent_mission(id),
  CONSTRAINT fk_agent_compensation_task FOREIGN KEY (agent_task_id) REFERENCES agent_task(id),
  CONSTRAINT fk_agent_compensation_step FOREIGN KEY (step_run_id) REFERENCES agent_step_run(id)
);

CREATE TABLE mission_mutation_idempotency (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  action_key VARCHAR(128) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  completed BOOLEAN NOT NULL DEFAULT FALSE,
  response_json JSON NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  UNIQUE KEY uq_mission_mutation_idempotency (user_id, action_key, idempotency_key),
  INDEX idx_mission_mutation_created (created_at)
);

ALTER TABLE agent_task
  ADD COLUMN mission_id BIGINT NULL,
  ADD COLUMN trigger_occurrence_id BIGINT NULL,
  ADD COLUMN delegation_grant_version INT NULL,
  ADD COLUMN execution_mode VARCHAR(24) NOT NULL DEFAULT 'INTERACTIVE',
  ADD INDEX idx_agent_task_mission_created (mission_id, created_at),
  ADD UNIQUE KEY uq_agent_task_trigger_occurrence (trigger_occurrence_id),
  ADD CONSTRAINT fk_agent_task_mission FOREIGN KEY (mission_id) REFERENCES agent_mission(id),
  ADD CONSTRAINT fk_agent_task_occurrence FOREIGN KEY (trigger_occurrence_id) REFERENCES mission_trigger_occurrence(id);

ALTER TABLE mission_trigger_occurrence
  ADD CONSTRAINT fk_mission_occurrence_task FOREIGN KEY (agent_task_id) REFERENCES agent_task(id);
