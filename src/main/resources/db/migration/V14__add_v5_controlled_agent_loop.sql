CREATE TABLE agent_task (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  project_id BIGINT NULL,
  conversation_id BIGINT NULL,
  title VARCHAR(255) NOT NULL,
  goal TEXT NOT NULL,
  completion_criteria JSON NOT NULL,
  constraints_json JSON NOT NULL,
  template_id VARCHAR(64) NULL,
  status VARCHAR(40) NOT NULL,
  risk_level VARCHAR(20) NOT NULL,
  autonomy_level TINYINT NOT NULL,
  active_plan_version INT NULL,
  current_run_id BIGINT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  deleted_at DATETIME(6) NULL,
  INDEX idx_agent_task_user_status_updated (user_id, status, updated_at),
  INDEX idx_agent_task_project_updated (project_id, updated_at)
);

CREATE TABLE agent_plan (
  id BIGINT PRIMARY KEY,
  agent_task_id BIGINT NOT NULL,
  version INT NOT NULL,
  source VARCHAR(20) NOT NULL,
  goal_snapshot TEXT NOT NULL,
  completion_criteria JSON NOT NULL,
  constraints_json JSON NOT NULL,
  access_scope JSON NOT NULL,
  budget_json JSON NOT NULL,
  change_summary JSON NOT NULL,
  approval_status VARCHAR(20) NOT NULL,
  approved_by BIGINT NULL,
  approved_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  UNIQUE KEY uq_agent_plan_task_version (agent_task_id, version),
  CONSTRAINT fk_agent_plan_task FOREIGN KEY (agent_task_id) REFERENCES agent_task(id)
);

CREATE TABLE agent_plan_step (
  id BIGINT PRIMARY KEY,
  agent_plan_id BIGINT NOT NULL,
  step_key VARCHAR(64) NOT NULL,
  position INT NOT NULL,
  step_type VARCHAR(30) NOT NULL,
  title VARCHAR(255) NOT NULL,
  purpose TEXT NOT NULL,
  tool_ref VARCHAR(255) NULL,
  input_template JSON NOT NULL,
  dependencies_json JSON NOT NULL,
  risk_level VARCHAR(20) NOT NULL,
  expected_artifact JSON NOT NULL,
  write_intent BOOLEAN NOT NULL DEFAULT FALSE,
  UNIQUE KEY uq_agent_plan_step_key (agent_plan_id, step_key),
  INDEX idx_agent_plan_step_position (agent_plan_id, position),
  CONSTRAINT fk_agent_plan_step_plan FOREIGN KEY (agent_plan_id) REFERENCES agent_plan(id)
);

CREATE TABLE agent_task_run (
  id BIGINT PRIMARY KEY,
  agent_task_id BIGINT NOT NULL,
  plan_version INT NOT NULL,
  status VARCHAR(40) NOT NULL,
  trigger_source VARCHAR(30) NOT NULL,
  permission_snapshot JSON NOT NULL,
  budget_limit JSON NOT NULL,
  budget_used JSON NOT NULL,
  no_progress_count INT NOT NULL DEFAULT 0,
  lease_owner VARCHAR(128) NULL,
  lease_expires_at DATETIME(6) NULL,
  heartbeat_at DATETIME(6) NULL,
  started_at DATETIME(6) NULL,
  paused_at DATETIME(6) NULL,
  finished_at DATETIME(6) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  INDEX idx_agent_run_recovery (status, lease_expires_at),
  INDEX idx_agent_run_task (agent_task_id, created_at),
  CONSTRAINT fk_agent_run_task FOREIGN KEY (agent_task_id) REFERENCES agent_task(id)
);

ALTER TABLE agent_task
  ADD CONSTRAINT fk_agent_task_current_run FOREIGN KEY (current_run_id) REFERENCES agent_task_run(id);

CREATE TABLE agent_step_run (
  id BIGINT PRIMARY KEY,
  task_run_id BIGINT NOT NULL,
  plan_step_id BIGINT NOT NULL,
  attempt INT NOT NULL,
  status VARCHAR(40) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  input_summary JSON NOT NULL,
  tool_call_ref VARCHAR(255) NULL,
  tool_result_ref VARCHAR(255) NULL,
  output_summary JSON NOT NULL,
  result_fingerprint VARCHAR(128) NULL,
  error_code VARCHAR(80) NULL,
  error_detail_ref VARCHAR(255) NULL,
  started_at DATETIME(6) NULL,
  finished_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  UNIQUE KEY uq_agent_step_run_attempt (task_run_id, plan_step_id, attempt),
  UNIQUE KEY uq_agent_step_idempotency (idempotency_key),
  INDEX idx_agent_step_run_status (task_run_id, status),
  CONSTRAINT fk_agent_step_run_run FOREIGN KEY (task_run_id) REFERENCES agent_task_run(id),
  CONSTRAINT fk_agent_step_run_step FOREIGN KEY (plan_step_id) REFERENCES agent_plan_step(id)
);

CREATE TABLE agent_checkpoint (
  id BIGINT PRIMARY KEY,
  task_run_id BIGINT NOT NULL,
  sequence_no INT NOT NULL,
  reason VARCHAR(40) NOT NULL,
  plan_version INT NOT NULL,
  current_step_id BIGINT NULL,
  completed_steps JSON NOT NULL,
  reusable_result_refs JSON NOT NULL,
  budget_used JSON NOT NULL,
  external_state_hashes JSON NOT NULL,
  created_at DATETIME(6) NOT NULL,
  UNIQUE KEY uq_agent_checkpoint_sequence (task_run_id, sequence_no),
  CONSTRAINT fk_agent_checkpoint_run FOREIGN KEY (task_run_id) REFERENCES agent_task_run(id)
);

CREATE TABLE agent_budget_ledger (
  id BIGINT PRIMARY KEY,
  task_run_id BIGINT NOT NULL,
  step_run_id BIGINT NULL,
  entry_type VARCHAR(30) NOT NULL,
  tool_calls INT NOT NULL DEFAULT 0,
  model_tokens BIGINT NOT NULL DEFAULT 0,
  cost_micros BIGINT NOT NULL DEFAULT 0,
  elapsed_millis BIGINT NOT NULL DEFAULT 0,
  retries INT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  INDEX idx_agent_budget_run_created (task_run_id, created_at),
  CONSTRAINT fk_agent_budget_run FOREIGN KEY (task_run_id) REFERENCES agent_task_run(id),
  CONSTRAINT fk_agent_budget_step FOREIGN KEY (step_run_id) REFERENCES agent_step_run(id)
);

CREATE TABLE change_preview (
  id BIGINT PRIMARY KEY,
  agent_task_id BIGINT NOT NULL,
  task_run_id BIGINT NOT NULL,
  step_run_id BIGINT NOT NULL,
  pending_operation_id BIGINT NULL,
  resource_ref VARCHAR(512) NOT NULL,
  action_type VARCHAR(80) NOT NULL,
  before_summary JSON NOT NULL,
  after_summary JSON NOT NULL,
  impact_summary TEXT NOT NULL,
  risk_level VARCHAR(20) NOT NULL,
  undo_supported BOOLEAN NOT NULL DEFAULT FALSE,
  compensation_metadata JSON NOT NULL,
  status VARCHAR(20) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  resolved_at DATETIME(6) NULL,
  UNIQUE KEY uq_change_preview_idempotency (idempotency_key),
  INDEX idx_change_preview_task_status (agent_task_id, status),
  CONSTRAINT fk_change_preview_task FOREIGN KEY (agent_task_id) REFERENCES agent_task(id),
  CONSTRAINT fk_change_preview_run FOREIGN KEY (task_run_id) REFERENCES agent_task_run(id),
  CONSTRAINT fk_change_preview_step FOREIGN KEY (step_run_id) REFERENCES agent_step_run(id)
);

CREATE TABLE agent_artifact (
  id BIGINT PRIMARY KEY,
  agent_task_id BIGINT NOT NULL,
  task_run_id BIGINT NOT NULL,
  step_run_id BIGINT NULL,
  artifact_type VARCHAR(40) NOT NULL,
  title VARCHAR(255) NOT NULL,
  summary TEXT NOT NULL,
  content_ref VARCHAR(512) NULL,
  evidence_refs JSON NOT NULL,
  metadata_json JSON NOT NULL,
  created_at DATETIME(6) NOT NULL,
  INDEX idx_agent_artifact_task_created (agent_task_id, created_at),
  CONSTRAINT fk_agent_artifact_task FOREIGN KEY (agent_task_id) REFERENCES agent_task(id),
  CONSTRAINT fk_agent_artifact_run FOREIGN KEY (task_run_id) REFERENCES agent_task_run(id),
  CONSTRAINT fk_agent_artifact_step FOREIGN KEY (step_run_id) REFERENCES agent_step_run(id)
);

CREATE TABLE agent_task_event (
  id BIGINT PRIMARY KEY,
  agent_task_id BIGINT NOT NULL,
  sequence_no BIGINT NOT NULL,
  event_type VARCHAR(80) NOT NULL,
  payload_json JSON NOT NULL,
  created_at DATETIME(6) NOT NULL,
  UNIQUE KEY uq_agent_event_sequence (agent_task_id, sequence_no),
  INDEX idx_agent_event_task_created (agent_task_id, created_at),
  CONSTRAINT fk_agent_event_task FOREIGN KEY (agent_task_id) REFERENCES agent_task(id)
);

CREATE TABLE agent_task_idempotency (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  action_key VARCHAR(100) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  agent_task_id BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  UNIQUE KEY uq_agent_idempotency (user_id, action_key, idempotency_key),
  CONSTRAINT fk_agent_idempotency_task FOREIGN KEY (agent_task_id) REFERENCES agent_task(id)
);
