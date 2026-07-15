package com.example.aiapp.agenttask.infrastructure.repository;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.example.aiapp.agenttask.domain.AgentBudget;
import com.example.aiapp.agenttask.domain.AgentBudgetUsage;
import com.example.aiapp.agenttask.domain.AgentStepStatus;
import com.example.aiapp.agenttask.domain.AgentTaskStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class AgentTaskRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final TypeReference<List<Object>> LIST_TYPE = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AgentTaskRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public long nextId() {
        return IdWorker.getId();
    }

    public long createTask(
            long id,
            long userId,
            Long projectId,
            Long conversationId,
            String title,
            String goal,
            List<Object> completionCriteria,
            Map<String, Object> constraints,
            String templateId,
            String riskLevel,
            int autonomyLevel
    ) {
        LocalDateTime now = now();
        jdbc.update("""
                INSERT INTO agent_task
                  (id, user_id, project_id, conversation_id, title, goal, completion_criteria,
                   constraints_json, template_id, status, risk_level, autonomy_level,
                   active_plan_version, current_run_id, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, 0, ?, ?)
                """, id, userId, projectId, conversationId, title, goal, json(completionCriteria),
                json(constraints), templateId, AgentTaskStatus.DRAFT.name(), riskLevel, autonomyLevel, now, now);
        return id;
    }

    public Optional<Long> findIdempotentTask(long userId, String actionKey, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return Optional.empty();
        List<Long> ids = jdbc.query("""
                SELECT agent_task_id FROM agent_task_idempotency
                WHERE user_id=? AND action_key=? AND idempotency_key=?
                """, (rs, rowNum) -> rs.getLong(1), userId, actionKey, idempotencyKey);
        return ids.stream().findFirst();
    }

    public void saveIdempotency(long userId, String actionKey, String idempotencyKey, long taskId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return;
        try {
            jdbc.update("""
                    INSERT INTO agent_task_idempotency
                      (id, user_id, action_key, idempotency_key, agent_task_id, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, nextId(), userId, actionKey, idempotencyKey, taskId, now());
        } catch (DuplicateKeyException ignored) {
            // The unique key is the cross-request serialization point.
        }
    }

    public boolean reserveIdempotency(long userId, String actionKey, String idempotencyKey, long taskId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key 不能为空。");
        }
        try {
            jdbc.update("""
                    INSERT INTO agent_task_idempotency
                      (id, user_id, action_key, idempotency_key, agent_task_id, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, nextId(), userId, actionKey, idempotencyKey, taskId, now());
            return true;
        } catch (DuplicateKeyException ignored) {
            return false;
        }
    }

    public Optional<TaskRecord> findTask(long taskId, long userId) {
        List<TaskRecord> rows = jdbc.query("""
                SELECT id, user_id, project_id, conversation_id, title, goal, completion_criteria,
                       constraints_json, template_id, status, risk_level, autonomy_level,
                       active_plan_version, current_run_id, version, created_at, updated_at
                FROM agent_task WHERE id=? AND user_id=? AND deleted_at IS NULL
                """, taskMapper(), taskId, userId);
        return rows.stream().findFirst();
    }

    public Optional<TaskRecord> findTask(long taskId) {
        List<TaskRecord> rows = jdbc.query("""
                SELECT id, user_id, project_id, conversation_id, title, goal, completion_criteria,
                       constraints_json, template_id, status, risk_level, autonomy_level,
                       active_plan_version, current_run_id, version, created_at, updated_at
                FROM agent_task WHERE id=? AND deleted_at IS NULL
                """, taskMapper(), taskId);
        return rows.stream().findFirst();
    }

    public List<TaskRecord> listTasks(long userId, String status, int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        if (status == null || status.isBlank()) {
            return jdbc.query("""
                    SELECT id, user_id, project_id, conversation_id, title, goal, completion_criteria,
                           constraints_json, template_id, status, risk_level, autonomy_level,
                           active_plan_version, current_run_id, version, created_at, updated_at
                    FROM agent_task WHERE user_id=? AND deleted_at IS NULL
                    ORDER BY updated_at DESC LIMIT ?
                    """, taskMapper(), userId, boundedLimit);
        }
        return jdbc.query("""
                SELECT id, user_id, project_id, conversation_id, title, goal, completion_criteria,
                       constraints_json, template_id, status, risk_level, autonomy_level,
                       active_plan_version, current_run_id, version, created_at, updated_at
                FROM agent_task WHERE user_id=? AND status=? AND deleted_at IS NULL
                ORDER BY updated_at DESC LIMIT ?
                """, taskMapper(), userId, status, boundedLimit);
    }

    public boolean transitionTask(long taskId, long expectedVersion, AgentTaskStatus from, AgentTaskStatus to) {
        return jdbc.update("""
                UPDATE agent_task SET status=?, version=version+1, updated_at=?
                WHERE id=? AND version=? AND status=? AND deleted_at IS NULL
                """, to.name(), now(), taskId, expectedVersion, from.name()) == 1;
    }

    public boolean transitionTaskFromCurrent(long taskId, AgentTaskStatus from, AgentTaskStatus to) {
        return jdbc.update("""
                UPDATE agent_task SET status=?, version=version+1, updated_at=?
                WHERE id=? AND status=? AND deleted_at IS NULL
                """, to.name(), now(), taskId, from.name()) == 1;
    }

    public long insertPlan(
            long taskId,
            int version,
            String source,
            String goal,
            List<Object> criteria,
            Map<String, Object> constraints,
            Map<String, Object> accessScope,
            AgentBudget budget,
            Map<String, Object> changeSummary,
            List<Map<String, Object>> steps
    ) {
        long planId = nextId();
        jdbc.update("""
                INSERT INTO agent_plan
                  (id, agent_task_id, version, source, goal_snapshot, completion_criteria,
                   constraints_json, access_scope, budget_json, change_summary, approval_status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
                """, planId, taskId, version, source, goal, json(criteria), json(constraints),
                json(accessScope), json(budget.toMap()), json(changeSummary), now());
        int position = 0;
        for (Map<String, Object> step : steps) {
            position++;
            jdbc.update("""
                    INSERT INTO agent_plan_step
                      (id, agent_plan_id, step_key, position, step_type, title, purpose, tool_ref,
                       input_template, dependencies_json, risk_level, expected_artifact, write_intent)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, nextId(), planId, text(step, "stepKey", "step-" + position), position,
                    text(step, "stepType", "TOOL"), text(step, "title", "步骤 " + position),
                    text(step, "purpose", "推进任务"), nullableText(step.get("toolRef")),
                    json(map(step.get("inputTemplate"))), json(list(step.get("dependencies"))),
                    text(step, "riskLevel", "READ"), json(map(step.get("expectedArtifact"))),
                    bool(step.get("writeIntent")));
        }
        jdbc.update("""
                UPDATE agent_task SET active_plan_version=?, version=version+1, updated_at=? WHERE id=?
                """, version, now(), taskId);
        return planId;
    }

    public Optional<PlanRecord> findPlan(long taskId, int version) {
        List<PlanRecord> rows = jdbc.query("""
                SELECT id, agent_task_id, version, source, goal_snapshot, completion_criteria,
                       constraints_json, access_scope, budget_json, change_summary, approval_status,
                       approved_by, approved_at, created_at
                FROM agent_plan WHERE agent_task_id=? AND version=?
                """, planMapper(), taskId, version);
        if (rows.isEmpty()) return Optional.empty();
        PlanRecord value = rows.get(0);
        return Optional.of(value.withSteps(findPlanSteps(value.id())));
    }

    public List<PlanStepRecord> findPlanSteps(long planId) {
        return jdbc.query("""
                SELECT id, agent_plan_id, step_key, position, step_type, title, purpose, tool_ref,
                       input_template, dependencies_json, risk_level, expected_artifact, write_intent
                FROM agent_plan_step WHERE agent_plan_id=? ORDER BY position ASC
                """, planStepMapper(), planId);
    }

    public void approvePlan(long planId, long userId) {
        jdbc.update("""
                UPDATE agent_plan SET approval_status='APPROVED', approved_by=?, approved_at=? WHERE id=?
                """, userId, now(), planId);
    }

    public long createRun(long taskId, int planVersion, Map<String, Object> permissions, AgentBudget budget) {
        long runId = nextId();
        LocalDateTime now = now();
        jdbc.update("""
                INSERT INTO agent_task_run
                  (id, agent_task_id, plan_version, status, trigger_source, permission_snapshot,
                   budget_limit, budget_used, no_progress_count, version, created_at, updated_at)
                VALUES (?, ?, ?, 'READY', 'USER', ?, ?, ?, 0, 0, ?, ?)
                """, runId, taskId, planVersion, json(permissions), json(budget.toMap()),
                json(AgentBudgetUsage.zero().toMap()), now, now);
        jdbc.update("UPDATE agent_task SET current_run_id=?, updated_at=? WHERE id=?", runId, now, taskId);
        return runId;
    }

    public Optional<RunRecord> findRun(long runId) {
        List<RunRecord> rows = jdbc.query("""
                SELECT id, agent_task_id, plan_version, status, trigger_source, permission_snapshot,
                       budget_limit, budget_used, no_progress_count, lease_owner, lease_expires_at,
                       heartbeat_at, started_at, paused_at, finished_at, version, created_at, updated_at
                FROM agent_task_run WHERE id=?
                """, runMapper(), runId);
        return rows.stream().findFirst();
    }

    public boolean updateRunStatus(long runId, String from, String to) {
        LocalDateTime now = now();
        String timestampColumn = switch (to) {
            case "RUNNING" -> "started_at=COALESCE(started_at, ?), ";
            case "PAUSED" -> "paused_at=?, ";
            case "COMPLETED", "PARTIALLY_COMPLETED", "FAILED", "CANCELLED" -> "finished_at=?, ";
            default -> "heartbeat_at=?, ";
        };
        return jdbc.update("UPDATE agent_task_run SET status=?, " + timestampColumn +
                "version=version+1, updated_at=? WHERE id=? AND status=?", to, now, now, runId, from) == 1;
    }

    public boolean acquireLease(long runId, String owner, int seconds) {
        LocalDateTime now = now();
        return jdbc.update("""
                UPDATE agent_task_run
                SET lease_owner=?, lease_expires_at=?, heartbeat_at=?, updated_at=?
                WHERE id=? AND status='RUNNING'
                  AND (lease_owner IS NULL OR lease_expires_at < ? OR lease_owner=?)
                """, owner, now.plusSeconds(seconds), now, now, runId, now, owner) == 1;
    }

    public boolean heartbeat(long runId, String owner, int seconds) {
        LocalDateTime now = now();
        return jdbc.update("""
                UPDATE agent_task_run SET lease_expires_at=?, heartbeat_at=?, updated_at=?
                WHERE id=? AND lease_owner=? AND status IN ('RUNNING','PAUSING')
                """, now.plusSeconds(seconds), now, now, runId, owner) == 1;
    }

    public void releaseLease(long runId, String owner) {
        jdbc.update("""
                UPDATE agent_task_run SET lease_owner=NULL, lease_expires_at=NULL, updated_at=?
                WHERE id=? AND lease_owner=?
                """, now(), runId, owner);
    }

    public List<Long> findRecoverableRunIds(int limit) {
        return jdbc.query("""
                SELECT id FROM agent_task_run
                WHERE status IN ('RUNNING','PAUSING') AND (lease_owner IS NULL OR lease_expires_at < ?)
                ORDER BY updated_at ASC LIMIT ?
                """, (rs, rowNum) -> rs.getLong(1), now(), Math.max(1, Math.min(limit, 100)));
    }

    public Optional<PlanStepRecord> findNextStep(long runId, long planId) {
        List<PlanStepRecord> rows = jdbc.query("""
                SELECT s.id, s.agent_plan_id, s.step_key, s.position, s.step_type, s.title, s.purpose,
                       s.tool_ref, s.input_template, s.dependencies_json, s.risk_level,
                       s.expected_artifact, s.write_intent
                FROM agent_plan_step s
                WHERE s.agent_plan_id=?
                  AND NOT EXISTS (
                    SELECT 1 FROM agent_step_run r
                    WHERE r.task_run_id=? AND r.plan_step_id=s.id
                      AND r.status IN ('SUCCEEDED','WAITING_CONFIRMATION','WAITING_INPUT','RUNNING','CANCELLED')
                  )
                ORDER BY s.position ASC LIMIT 1
                """, planStepMapper(), planId, runId);
        return rows.stream().findFirst();
    }

    public Optional<StepRunRecord> findRunningStep(long runId) {
        List<StepRunRecord> rows = jdbc.query("""
                SELECT id, task_run_id, plan_step_id, attempt, status, idempotency_key,
                       input_summary, output_summary, tool_result_ref, result_fingerprint, error_code,
                       started_at, finished_at, created_at
                FROM agent_step_run
                WHERE task_run_id=? AND status='RUNNING'
                ORDER BY created_at ASC LIMIT 1
                """, stepRunMapper(), runId);
        return rows.stream().findFirst();
    }

    public Optional<PlanStepRecord> findPlanStep(long stepId) {
        List<PlanStepRecord> rows = jdbc.query("""
                SELECT id, agent_plan_id, step_key, position, step_type, title, purpose, tool_ref,
                       input_template, dependencies_json, risk_level, expected_artifact, write_intent
                FROM agent_plan_step WHERE id=?
                """, planStepMapper(), stepId);
        return rows.stream().findFirst();
    }

    public int nextAttempt(long runId, long stepId) {
        Integer value = jdbc.queryForObject("""
                SELECT COALESCE(MAX(attempt), 0) + 1 FROM agent_step_run
                WHERE task_run_id=? AND plan_step_id=?
                """, Integer.class, runId, stepId);
        return value == null ? 1 : value;
    }

    public long createStepRun(long runId, long stepId, int attempt, String idempotencyKey, Map<String, Object> input) {
        long id = nextId();
        jdbc.update("""
                INSERT INTO agent_step_run
                  (id, task_run_id, plan_step_id, attempt, status, idempotency_key,
                   input_summary, output_summary, started_at, created_at)
                VALUES (?, ?, ?, ?, 'RUNNING', ?, ?, ?, ?, ?)
                """, id, runId, stepId, attempt, idempotencyKey, json(input), json(Map.of()), now(), now());
        return id;
    }

    public void finishStep(
            long stepRunId,
            AgentStepStatus status,
            Map<String, Object> output,
            String toolResultRef,
            String fingerprint,
            String errorCode
    ) {
        jdbc.update("""
                UPDATE agent_step_run
                SET status=?, output_summary=?, tool_result_ref=?, result_fingerprint=?,
                    error_code=?, finished_at=? WHERE id=? AND status IN ('RUNNING','WAITING_CONFIRMATION')
                """, status.name(), json(output), toolResultRef, fingerprint, errorCode, now(), stepRunId);
    }

    public boolean stageStepResult(long stepRunId, Map<String, Object> output) {
        return jdbc.update("""
                UPDATE agent_step_run SET output_summary=?
                WHERE id=? AND status='RUNNING'
                """, json(output), stepRunId) == 1;
    }

    public Optional<StepRunRecord> findWaitingStep(long runId) {
        List<StepRunRecord> rows = jdbc.query("""
                SELECT id, task_run_id, plan_step_id, attempt, status, idempotency_key,
                       input_summary, output_summary, tool_result_ref, result_fingerprint, error_code,
                       started_at, finished_at, created_at
                FROM agent_step_run WHERE task_run_id=?
                  AND status IN ('WAITING_CONFIRMATION','WAITING_INPUT')
                ORDER BY created_at DESC LIMIT 1
                """, stepRunMapper(), runId);
        return rows.stream().findFirst();
    }

    public Optional<String> previousFingerprint(long runId, String toolRef) {
        List<String> values = jdbc.query("""
                SELECT r.result_fingerprint
                FROM agent_step_run r JOIN agent_plan_step s ON s.id=r.plan_step_id
                WHERE r.task_run_id=? AND s.tool_ref=? AND r.status='SUCCEEDED'
                  AND r.result_fingerprint IS NOT NULL
                ORDER BY r.finished_at DESC LIMIT 1
                """, (rs, rowNum) -> rs.getString(1), runId, toolRef);
        return values.stream().findFirst();
    }

    public void appendBudget(
            long runId,
            Long stepRunId,
            String type,
            AgentBudgetUsage delta,
            AgentBudgetUsage aggregate,
            int noProgressCount
    ) {
        jdbc.update("""
                INSERT INTO agent_budget_ledger
                  (id, task_run_id, step_run_id, entry_type, tool_calls, model_tokens,
                   cost_micros, elapsed_millis, retries, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, nextId(), runId, stepRunId, type, delta.toolCalls(), delta.modelTokens(),
                delta.costMicros(), delta.elapsedMillis(), delta.retries(), now());
        jdbc.update("""
                UPDATE agent_task_run SET budget_used=?, no_progress_count=?, version=version+1, updated_at=?
                WHERE id=?
                """, json(aggregate.toMap()), noProgressCount, now(), runId);
    }

    @Transactional
    public Optional<AgentBudgetUsage> reserveBudget(long runId, Long stepRunId, int toolCalls, int retries) {
        RunRecord run = findRunForUpdate(runId).orElse(null);
        if (run == null || !"RUNNING".equals(run.status())) return Optional.empty();
        if (stepRunId != null && hasBudgetEntry(stepRunId, "RESERVED")) return Optional.of(run.budgetUsed());
        AgentBudgetUsage next = run.budgetUsed().plus(0, toolCalls, 0, 0, 0, retries);
        if (exceeds(run.budgetLimit(), next)) return Optional.empty();
        insertBudgetLedger(runId, stepRunId, "RESERVED", new AgentBudgetUsage(0, toolCalls, 0, 0, 0, retries));
        updateBudgetAggregate(runId, next, run.noProgressCount());
        return Optional.of(next);
    }

    public boolean hasBudgetReservation(long stepRunId) {
        return hasBudgetEntry(stepRunId, "RESERVED");
    }

    public boolean hasBudgetSettlement(long stepRunId) {
        return hasBudgetEntry(stepRunId, "SETTLED");
    }

    /**
     * Charges the wall-clock time of an interrupted RUNNING step before it is
     * recovered.  A hard-killed worker never reaches normal settlement, so
     * without this charge repeated restarts could bypass maxWallTimeMillis.
     * The charge is cumulative and idempotent across multiple recoveries of
     * the same step.
     */
    @Transactional
    public Optional<AgentBudgetUsage> chargeRecoveryElapsed(long runId, long stepRunId, long totalElapsedMillis) {
        RunRecord run = findRunForUpdate(runId).orElse(null);
        if (run == null || !"RUNNING".equals(run.status())) return Optional.empty();
        Long alreadyCharged = jdbc.queryForObject("""
                SELECT COALESCE(SUM(elapsed_millis), 0) FROM agent_budget_ledger
                WHERE step_run_id=? AND entry_type='RECOVERY_CHARGE'
                """, Long.class, stepRunId);
        long deltaMillis = Math.max(0, totalElapsedMillis - (alreadyCharged == null ? 0 : alreadyCharged));
        if (deltaMillis == 0) return Optional.of(run.budgetUsed());
        AgentBudgetUsage next = run.budgetUsed().plus(0, 0, 0, 0, deltaMillis, 0);
        if (exceeds(run.budgetLimit(), next)) {
            insertBudgetLedger(runId, stepRunId, "RECOVERY_CHARGE_REJECTED", AgentBudgetUsage.zero());
            return Optional.empty();
        }
        insertBudgetLedger(runId, stepRunId, "RECOVERY_CHARGE",
                new AgentBudgetUsage(0, 0, 0, 0, deltaMillis, 0));
        updateBudgetAggregate(runId, next, run.noProgressCount());
        return Optional.of(next);
    }

    @Transactional
    public Optional<AgentBudgetUsage> settleBudget(
            long runId,
            Long stepRunId,
            int completedSteps,
            long modelTokens,
            long costMicros,
            long elapsedMillis,
            int noProgressCount
    ) {
        RunRecord run = findRunForUpdate(runId).orElse(null);
        if (run == null || !List.of("RUNNING", "PAUSING", "AWAITING_CONFIRMATION").contains(run.status())) return Optional.empty();
        if (stepRunId != null && hasBudgetEntry(stepRunId, "SETTLED")) return Optional.of(run.budgetUsed());
        AgentBudgetUsage delta = new AgentBudgetUsage(completedSteps, 0, modelTokens, costMicros, elapsedMillis, 0);
        AgentBudgetUsage next = run.budgetUsed().plus(completedSteps, 0, modelTokens, costMicros, elapsedMillis, 0);
        if (exceeds(run.budgetLimit(), next)) {
            insertBudgetLedger(runId, stepRunId, "SETTLEMENT_REJECTED", AgentBudgetUsage.zero());
            return Optional.empty();
        }
        insertBudgetLedger(runId, stepRunId, "SETTLED", delta);
        updateBudgetAggregate(runId, next, noProgressCount);
        return Optional.of(next);
    }

    private boolean hasBudgetEntry(long stepRunId, String entryType) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_budget_ledger
                WHERE step_run_id=? AND entry_type=?
                """, Integer.class, stepRunId, entryType);
        return count != null && count > 0;
    }

    private Optional<RunRecord> findRunForUpdate(long runId) {
        List<RunRecord> rows = jdbc.query("""
                SELECT id, agent_task_id, plan_version, status, trigger_source, permission_snapshot,
                       budget_limit, budget_used, no_progress_count, lease_owner, lease_expires_at,
                       heartbeat_at, started_at, paused_at, finished_at, version, created_at, updated_at
                FROM agent_task_run WHERE id=? FOR UPDATE
                """, runMapper(), runId);
        return rows.stream().findFirst();
    }

    private void insertBudgetLedger(long runId, Long stepRunId, String type, AgentBudgetUsage delta) {
        jdbc.update("""
                INSERT INTO agent_budget_ledger
                  (id, task_run_id, step_run_id, entry_type, tool_calls, model_tokens,
                   cost_micros, elapsed_millis, retries, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, nextId(), runId, stepRunId, type, delta.toolCalls(), delta.modelTokens(),
                delta.costMicros(), delta.elapsedMillis(), delta.retries(), now());
    }

    private void updateBudgetAggregate(long runId, AgentBudgetUsage usage, int noProgressCount) {
        jdbc.update("""
                UPDATE agent_task_run SET budget_used=?, no_progress_count=?, version=version+1, updated_at=?
                WHERE id=?
                """, json(usage.toMap()), noProgressCount, now(), runId);
    }

    private static boolean exceeds(AgentBudget limit, AgentBudgetUsage used) {
        return used.completedSteps() > limit.maxSteps()
                || used.toolCalls() > limit.maxToolCalls()
                || used.modelTokens() > limit.maxModelTokens()
                || used.costMicros() > limit.maxCostMicros()
                || used.elapsedMillis() > limit.maxWallTimeMillis();
    }

    public long createCheckpoint(
            long runId,
            String reason,
            int planVersion,
            Long currentStepId,
            AgentBudgetUsage budget
    ) {
        return createCheckpoint(runId, reason, planVersion, currentStepId, budget, latestExternalState(runId));
    }

    public long createCheckpoint(
            long runId,
            String reason,
            int planVersion,
            Long currentStepId,
            AgentBudgetUsage budget,
            Map<String, Object> externalStateHashes
    ) {
        Integer sequence = jdbc.queryForObject("""
                SELECT COALESCE(MAX(sequence_no), 0) + 1 FROM agent_checkpoint WHERE task_run_id=?
                """, Integer.class, runId);
        List<Map<String, Object>> completed = jdbc.query("""
                SELECT r.id, s.step_key, s.title, r.tool_result_ref
                FROM agent_step_run r JOIN agent_plan_step s ON s.id=r.plan_step_id
                WHERE r.task_run_id=? AND r.status='SUCCEEDED' ORDER BY s.position
                """, (rs, rowNum) -> Map.of(
                "stepRunId", rs.getLong(1),
                "stepKey", rs.getString(2),
                "title", rs.getString(3),
                "toolResultRef", rs.getString(4) == null ? "" : rs.getString(4)
        ), runId);
        long id = nextId();
        jdbc.update("""
                INSERT INTO agent_checkpoint
                  (id, task_run_id, sequence_no, reason, plan_version, current_step_id,
                   completed_steps, reusable_result_refs, budget_used, external_state_hashes, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, runId, sequence == null ? 1 : sequence, reason, planVersion, currentStepId,
                json(completed), json(List.of()), json(budget.toMap()), json(externalStateHashes), now());
        return id;
    }

    public Map<String, Object> latestExternalState(long runId) {
        List<Map<String, Object>> values = jdbc.query("""
                SELECT external_state_hashes FROM agent_checkpoint
                WHERE task_run_id=? ORDER BY sequence_no DESC LIMIT 1
                """, (rs, rowNum) -> readMap(rs.getString(1)), runId);
        return values.stream().findFirst().orElseGet(LinkedHashMap::new);
    }

    public long createChangePreview(
            long taskId,
            long runId,
            long stepRunId,
            Long pendingOperationId,
            String resourceRef,
            String actionType,
            Map<String, Object> before,
            Map<String, Object> after,
            String impact,
            String riskLevel,
            boolean undoSupported,
            Map<String, Object> compensation,
            String idempotencyKey
    ) {
        long id = nextId();
        jdbc.update("""
                INSERT INTO change_preview
                  (id, agent_task_id, task_run_id, step_run_id, pending_operation_id,
                   resource_ref, action_type, before_summary, after_summary, impact_summary,
                   risk_level, undo_supported, compensation_metadata, status, idempotency_key, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                """, id, taskId, runId, stepRunId, pendingOperationId, resourceRef, actionType,
                json(before), json(after), impact, riskLevel, undoSupported, json(compensation),
                idempotencyKey, now());
        return id;
    }

    public Optional<ChangePreviewRecord> findPendingPreview(long runId) {
        List<ChangePreviewRecord> rows = jdbc.query("""
                SELECT id, agent_task_id, task_run_id, step_run_id, pending_operation_id,
                       resource_ref, action_type, before_summary, after_summary, impact_summary,
                       risk_level, undo_supported, compensation_metadata, status,
                       idempotency_key, created_at, resolved_at
                FROM change_preview WHERE task_run_id=? AND status='PENDING'
                ORDER BY created_at DESC LIMIT 1
                """, changePreviewMapper(), runId);
        return rows.stream().findFirst();
    }

    public void resolvePreview(long previewId, String status) {
        jdbc.update("UPDATE change_preview SET status=?, resolved_at=? WHERE id=? AND status='PENDING'", status, now(), previewId);
    }

    public long saveInput(long taskId, long runId, String action, String input) {
        long id = nextId();
        jdbc.update("""
                INSERT INTO agent_task_input
                  (id, agent_task_id, task_run_id, action_type, input_text, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, taskId, runId, action, input == null ? "" : input, now());
        return id;
    }

    public Map<String, Object> latestInput(long runId) {
        List<Map<String, Object>> values = jdbc.query("""
                SELECT id, action_type, input_text, created_at
                FROM agent_task_input WHERE task_run_id=?
                ORDER BY created_at DESC LIMIT 1
                """, (rs, rowNum) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", rs.getLong(1));
            item.put("action", rs.getString(2));
            item.put("input", rs.getString(3));
            item.put("createdAt", iso(rs.getObject(4, LocalDateTime.class)));
            return item;
        }, runId);
        return values.stream().findFirst().orElseGet(LinkedHashMap::new);
    }

    public boolean retryLatestWaitingStep(long runId) {
        List<Long> ids = jdbc.query("""
                SELECT id FROM agent_step_run
                WHERE task_run_id=? AND status='WAITING_INPUT'
                ORDER BY created_at DESC LIMIT 1
                """, (rs, rowNum) -> rs.getLong(1), runId);
        if (ids.isEmpty()) return false;
        return jdbc.update("""
                UPDATE agent_step_run SET status='FAILED', error_code='USER_RETRY_REQUESTED'
                WHERE id=? AND status='WAITING_INPUT'
                """, ids.get(0)) == 1;
    }

    public boolean skipLatestFailedStep(long runId) {
        List<Long> ids = jdbc.query("""
                SELECT id FROM agent_step_run
                WHERE task_run_id=? AND status IN ('FAILED','WAITING_INPUT')
                ORDER BY created_at DESC LIMIT 1
                """, (rs, rowNum) -> rs.getLong(1), runId);
        if (ids.isEmpty()) return false;
        return jdbc.update("""
                UPDATE agent_step_run SET status='CANCELLED', finished_at=COALESCE(finished_at, ?)
                WHERE id=? AND status IN ('FAILED','WAITING_INPUT')
                """, now(), ids.get(0)) == 1;
    }

    public long createArtifact(
            long taskId,
            long runId,
            Long stepRunId,
            String type,
            String title,
            String summary,
            String contentRef,
            List<Object> evidenceRefs,
            Map<String, Object> metadata
    ) {
        if (stepRunId != null) {
            List<Long> existing = jdbc.query("""
                    SELECT id FROM agent_artifact
                    WHERE agent_task_id=? AND task_run_id=? AND step_run_id=? AND artifact_type=?
                    ORDER BY created_at ASC LIMIT 1
                    """, (rs, rowNum) -> rs.getLong(1), taskId, runId, stepRunId, type);
            if (!existing.isEmpty()) return existing.get(0);
        }
        long id = nextId();
        jdbc.update("""
                INSERT INTO agent_artifact
                  (id, agent_task_id, task_run_id, step_run_id, artifact_type, title,
                   summary, content_ref, evidence_refs, metadata_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, taskId, runId, stepRunId, type, title, summary, contentRef,
                json(evidenceRefs), json(metadata), now());
        return id;
    }

    public List<Map<String, Object>> listArtifacts(long taskId) {
        return jdbc.query("""
                SELECT id, task_run_id, step_run_id, artifact_type, title, summary,
                       content_ref, evidence_refs, metadata_json, created_at
                FROM agent_artifact WHERE agent_task_id=? ORDER BY created_at ASC
                """, (rs, rowNum) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", rs.getLong(1));
            item.put("taskRunId", rs.getLong(2));
            item.put("stepRunId", nullableLong(rs, 3));
            item.put("type", rs.getString(4));
            item.put("title", rs.getString(5));
            item.put("summary", rs.getString(6));
            item.put("contentRef", rs.getString(7));
            item.put("evidenceRefs", readList(rs.getString(8)));
            item.put("metadata", readMap(rs.getString(9)));
            item.put("createdAt", iso(rs.getObject(10, LocalDateTime.class)));
            return item;
        }, taskId);
    }

    public List<Map<String, Object>> listStepRuns(long runId) {
        return jdbc.query("""
                SELECT r.id, r.plan_step_id, s.step_key, s.position, s.title, s.purpose, s.tool_ref,
                       s.risk_level, s.write_intent, r.attempt, r.status, r.output_summary,
                       r.tool_result_ref, r.error_code, r.started_at, r.finished_at
                FROM agent_step_run r JOIN agent_plan_step s ON s.id=r.plan_step_id
                WHERE r.task_run_id=? ORDER BY s.position, r.attempt
                """, (rs, rowNum) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", rs.getLong(1));
            item.put("planStepId", rs.getLong(2));
            item.put("stepKey", rs.getString(3));
            item.put("position", rs.getInt(4));
            item.put("title", rs.getString(5));
            item.put("purpose", rs.getString(6));
            item.put("toolRef", rs.getString(7));
            item.put("riskLevel", rs.getString(8));
            item.put("writeIntent", rs.getBoolean(9));
            item.put("attempt", rs.getInt(10));
            item.put("status", rs.getString(11));
            item.put("output", readMap(rs.getString(12)));
            item.put("toolResultRef", rs.getString(13));
            item.put("errorCode", rs.getString(14));
            item.put("startedAt", iso(rs.getObject(15, LocalDateTime.class)));
            item.put("finishedAt", iso(rs.getObject(16, LocalDateTime.class)));
            return item;
        }, runId);
    }

    public long appendEvent(long taskId, String eventType, Map<String, Object> payload) {
        Long sequence = jdbc.queryForObject("""
                SELECT COALESCE(MAX(sequence_no), 0) + 1 FROM agent_task_event WHERE agent_task_id=?
                """, Long.class, taskId);
        long eventId = nextId();
        jdbc.update("""
                INSERT INTO agent_task_event
                  (id, agent_task_id, sequence_no, event_type, payload_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, eventId, taskId, sequence == null ? 1 : sequence, eventType, json(payload), now());
        return sequence == null ? 1 : sequence;
    }

    public List<EventRecord> listEvents(long taskId, long afterSequence, int limit) {
        return jdbc.query("""
                SELECT sequence_no, event_type, payload_json, created_at
                FROM agent_task_event WHERE agent_task_id=? AND sequence_no>?
                ORDER BY sequence_no ASC LIMIT ?
                """, (rs, rowNum) -> new EventRecord(
                rs.getLong(1), rs.getString(2), readMap(rs.getString(3)),
                rs.getObject(4, LocalDateTime.class)
        ), taskId, Math.max(0, afterSequence), Math.max(1, Math.min(limit, 1000)));
    }

    public List<Map<String, Object>> listAudit(long taskId, int limit) {
        return jdbc.query("""
                SELECT sequence_no, event_type, payload_json, created_at
                FROM agent_task_event WHERE agent_task_id=?
                ORDER BY sequence_no DESC LIMIT ?
                """, (rs, rowNum) -> Map.of(
                "sequence", rs.getLong(1),
                "eventType", rs.getString(2),
                "payload", readMap(rs.getString(3)),
                "createdAt", iso(rs.getObject(4, LocalDateTime.class))
        ), taskId, Math.max(1, Math.min(limit, 200)));
    }

    private RowMapper<TaskRecord> taskMapper() {
        return (rs, rowNum) -> new TaskRecord(
                rs.getLong("id"), rs.getLong("user_id"), nullableLong(rs, "project_id"),
                nullableLong(rs, "conversation_id"), rs.getString("title"), rs.getString("goal"),
                readList(rs.getString("completion_criteria")), readMap(rs.getString("constraints_json")),
                rs.getString("template_id"), AgentTaskStatus.valueOf(rs.getString("status")),
                rs.getString("risk_level"), rs.getInt("autonomy_level"),
                nullableInteger(rs, "active_plan_version"), nullableLong(rs, "current_run_id"),
                rs.getLong("version"), rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)
        );
    }

    private RowMapper<PlanRecord> planMapper() {
        return (rs, rowNum) -> new PlanRecord(
                rs.getLong("id"), rs.getLong("agent_task_id"), rs.getInt("version"),
                rs.getString("source"), rs.getString("goal_snapshot"),
                readList(rs.getString("completion_criteria")), readMap(rs.getString("constraints_json")),
                readMap(rs.getString("access_scope")), AgentBudget.from(readMap(rs.getString("budget_json"))),
                readMap(rs.getString("change_summary")), rs.getString("approval_status"),
                nullableLong(rs, "approved_by"), rs.getObject("approved_at", LocalDateTime.class),
                rs.getObject("created_at", LocalDateTime.class), List.of()
        );
    }

    private RowMapper<PlanStepRecord> planStepMapper() {
        return (rs, rowNum) -> new PlanStepRecord(
                rs.getLong("id"), rs.getLong("agent_plan_id"), rs.getString("step_key"),
                rs.getInt("position"), rs.getString("step_type"), rs.getString("title"),
                rs.getString("purpose"), rs.getString("tool_ref"), readMap(rs.getString("input_template")),
                readList(rs.getString("dependencies_json")), rs.getString("risk_level"),
                readMap(rs.getString("expected_artifact")), rs.getBoolean("write_intent")
        );
    }

    private RowMapper<RunRecord> runMapper() {
        return (rs, rowNum) -> new RunRecord(
                rs.getLong("id"), rs.getLong("agent_task_id"), rs.getInt("plan_version"),
                rs.getString("status"), rs.getString("trigger_source"),
                readMap(rs.getString("permission_snapshot")), AgentBudget.from(readMap(rs.getString("budget_limit"))),
                AgentBudgetUsage.from(readMap(rs.getString("budget_used"))), rs.getInt("no_progress_count"),
                rs.getString("lease_owner"), rs.getObject("lease_expires_at", LocalDateTime.class),
                rs.getObject("heartbeat_at", LocalDateTime.class), rs.getObject("started_at", LocalDateTime.class),
                rs.getObject("paused_at", LocalDateTime.class), rs.getObject("finished_at", LocalDateTime.class),
                rs.getLong("version"), rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)
        );
    }

    private RowMapper<StepRunRecord> stepRunMapper() {
        return (rs, rowNum) -> new StepRunRecord(
                rs.getLong("id"), rs.getLong("task_run_id"), rs.getLong("plan_step_id"),
                rs.getInt("attempt"), AgentStepStatus.valueOf(rs.getString("status")),
                rs.getString("idempotency_key"), readMap(rs.getString("input_summary")),
                readMap(rs.getString("output_summary")), rs.getString("tool_result_ref"),
                rs.getString("result_fingerprint"), rs.getString("error_code"),
                rs.getObject("started_at", LocalDateTime.class), rs.getObject("finished_at", LocalDateTime.class),
                rs.getObject("created_at", LocalDateTime.class)
        );
    }

    private RowMapper<ChangePreviewRecord> changePreviewMapper() {
        return (rs, rowNum) -> new ChangePreviewRecord(
                rs.getLong("id"), rs.getLong("agent_task_id"), rs.getLong("task_run_id"),
                rs.getLong("step_run_id"), nullableLong(rs, "pending_operation_id"),
                rs.getString("resource_ref"), rs.getString("action_type"),
                readMap(rs.getString("before_summary")), readMap(rs.getString("after_summary")),
                rs.getString("impact_summary"), rs.getString("risk_level"), rs.getBoolean("undo_supported"),
                readMap(rs.getString("compensation_metadata")), rs.getString("status"),
                rs.getString("idempotency_key"), rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("resolved_at", LocalDateTime.class)
        );
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exc) {
            throw new IllegalArgumentException("无法序列化 Agent Task 数据。", exc);
        }
    }

    private Map<String, Object> readMap(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (JsonProcessingException exc) {
            throw new IllegalStateException("Agent Task JSON 数据损坏。", exc);
        }
    }

    private List<Object> readList(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return objectMapper.readValue(value, LIST_TYPE);
        } catch (JsonProcessingException exc) {
            throw new IllegalStateException("Agent Task JSON 数据损坏。", exc);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> input ? new LinkedHashMap<>((Map<String, Object>) input) : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return value instanceof List<?> input ? new ArrayList<>((List<Object>) input) : new ArrayList<>();
    }

    private static String text(Map<String, Object> values, String key, String fallback) {
        Object value = values.get(key);
        String result = value == null ? "" : String.valueOf(value).trim();
        return result.isBlank() ? fallback : result;
    }

    private static String nullableText(Object value) {
        if (value == null) return null;
        String result = String.valueOf(value).trim();
        return result.isBlank() ? null : result;
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean booleanValue && booleanValue;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet rs, int column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static String iso(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC).toString();
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    public record TaskRecord(
            long id, long userId, Long projectId, Long conversationId, String title, String goal,
            List<Object> completionCriteria, Map<String, Object> constraints, String templateId,
            AgentTaskStatus status, String riskLevel, int autonomyLevel, Integer activePlanVersion,
            Long currentRunId, long version, LocalDateTime createdAt, LocalDateTime updatedAt
    ) { }

    public record PlanRecord(
            long id, long taskId, int version, String source, String goal, List<Object> completionCriteria,
            Map<String, Object> constraints, Map<String, Object> accessScope, AgentBudget budget,
            Map<String, Object> changeSummary, String approvalStatus, Long approvedBy,
            LocalDateTime approvedAt, LocalDateTime createdAt, List<PlanStepRecord> steps
    ) {
        PlanRecord withSteps(List<PlanStepRecord> value) {
            return new PlanRecord(id, taskId, version, source, goal, completionCriteria, constraints,
                    accessScope, budget, changeSummary, approvalStatus, approvedBy, approvedAt, createdAt, value);
        }
    }

    public record PlanStepRecord(
            long id, long planId, String stepKey, int position, String stepType, String title, String purpose,
            String toolRef, Map<String, Object> inputTemplate, List<Object> dependencies, String riskLevel,
            Map<String, Object> expectedArtifact, boolean writeIntent
    ) { }

    public record RunRecord(
            long id, long taskId, int planVersion, String status, String triggerSource,
            Map<String, Object> permissionSnapshot, AgentBudget budgetLimit, AgentBudgetUsage budgetUsed,
            int noProgressCount, String leaseOwner, LocalDateTime leaseExpiresAt, LocalDateTime heartbeatAt,
            LocalDateTime startedAt, LocalDateTime pausedAt, LocalDateTime finishedAt, long version,
            LocalDateTime createdAt, LocalDateTime updatedAt
    ) { }

    public record StepRunRecord(
            long id, long runId, long planStepId, int attempt, AgentStepStatus status, String idempotencyKey,
            Map<String, Object> input, Map<String, Object> output, String toolResultRef,
            String resultFingerprint, String errorCode, LocalDateTime startedAt, LocalDateTime finishedAt,
            LocalDateTime createdAt
    ) { }

    public record ChangePreviewRecord(
            long id, long taskId, long runId, long stepRunId, Long pendingOperationId,
            String resourceRef, String actionType, Map<String, Object> before, Map<String, Object> after,
            String impact, String riskLevel, boolean undoSupported, Map<String, Object> compensation,
            String status, String idempotencyKey, LocalDateTime createdAt, LocalDateTime resolvedAt
    ) { }

    public record EventRecord(long sequence, String eventType, Map<String, Object> payload, LocalDateTime createdAt) { }
}
