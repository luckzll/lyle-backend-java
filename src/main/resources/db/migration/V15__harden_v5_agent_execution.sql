-- Idempotency rows are transaction reservations. They must be insertable before
-- the Task row exists so concurrent create requests have a single winner.
ALTER TABLE agent_task_idempotency
  DROP FOREIGN KEY fk_agent_idempotency_task;

CREATE TABLE agent_task_input (
  id BIGINT PRIMARY KEY,
  agent_task_id BIGINT NOT NULL,
  task_run_id BIGINT NOT NULL,
  action_type VARCHAR(30) NOT NULL,
  input_text TEXT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  INDEX idx_agent_input_run_created (task_run_id, created_at),
  CONSTRAINT fk_agent_input_task FOREIGN KEY (agent_task_id) REFERENCES agent_task(id),
  CONSTRAINT fk_agent_input_run FOREIGN KEY (task_run_id) REFERENCES agent_task_run(id)
);
