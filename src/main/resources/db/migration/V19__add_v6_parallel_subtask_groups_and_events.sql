CREATE TABLE agent_subtask_group (
  id BIGINT PRIMARY KEY,
  task_run_id BIGINT NOT NULL,
  parent_step_run_id BIGINT NULL,
  status VARCHAR(32) NOT NULL,
  max_parallelism INT NOT NULL,
  branch_budget JSON NOT NULL,
  join_policy VARCHAR(32) NOT NULL,
  succeeded_count INT NOT NULL DEFAULT 0,
  failed_count INT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  completed_at DATETIME(6) NULL,
  INDEX idx_agent_subtask_group_run_created (task_run_id, created_at),
  CONSTRAINT fk_agent_subtask_group_run FOREIGN KEY (task_run_id) REFERENCES agent_task_run(id),
  CONSTRAINT fk_agent_subtask_group_parent_step FOREIGN KEY (parent_step_run_id) REFERENCES agent_step_run(id)
);

CREATE TABLE agent_subtask_group_member (
  subtask_group_id BIGINT NOT NULL,
  step_run_id BIGINT NOT NULL,
  branch_key VARCHAR(64) NOT NULL,
  branch_budget JSON NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (subtask_group_id, step_run_id),
  UNIQUE KEY uq_agent_subtask_group_branch (subtask_group_id, branch_key),
  CONSTRAINT fk_agent_subtask_member_group FOREIGN KEY (subtask_group_id) REFERENCES agent_subtask_group(id),
  CONSTRAINT fk_agent_subtask_member_step FOREIGN KEY (step_run_id) REFERENCES agent_step_run(id)
);

CREATE TABLE mission_event (
  id BIGINT PRIMARY KEY,
  mission_id BIGINT NOT NULL,
  sequence_no BIGINT NOT NULL,
  event_type VARCHAR(80) NOT NULL,
  status VARCHAR(32) NULL,
  payload_json JSON NOT NULL,
  created_at DATETIME(6) NOT NULL,
  UNIQUE KEY uq_mission_event_sequence (mission_id, sequence_no),
  INDEX idx_mission_event_created (mission_id, created_at),
  CONSTRAINT fk_mission_event_mission FOREIGN KEY (mission_id) REFERENCES agent_mission(id)
);
