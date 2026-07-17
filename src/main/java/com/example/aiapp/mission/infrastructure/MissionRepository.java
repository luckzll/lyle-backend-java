package com.example.aiapp.mission.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
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
public class MissionRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final TypeReference<List<Object>> LIST_TYPE = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public MissionRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public long nextId() {
        return IdWorker.getId();
    }

    public Optional<MutationIdempotencyRecord> findMutation(
            long userId,
            String actionKey,
            String idempotencyKey
    ) {
        List<MutationIdempotencyRecord> rows = jdbc.query("""
                SELECT completed, response_json
                FROM mission_mutation_idempotency
                WHERE user_id=? AND action_key=? AND idempotency_key=?
                """, (rs, rowNum) -> new MutationIdempotencyRecord(
                rs.getBoolean("completed"),
                readMap(rs.getString("response_json"))
        ), userId, actionKey, idempotencyKey);
        return rows.stream().findFirst();
    }

    public boolean reserveMutation(long userId, String actionKey, String idempotencyKey) {
        long id = nextId();
        LocalDateTime now = now();
        try {
            return jdbc.update("""
                    INSERT INTO mission_mutation_idempotency
                      (id, user_id, action_key, idempotency_key, completed,
                       response_json, created_at, updated_at)
                    VALUES (?, ?, ?, ?, FALSE, NULL, ?, ?)
                    """, id, userId, actionKey, idempotencyKey, now, now) == 1;
        } catch (DuplicateKeyException ignored) {
            return false;
        }
    }

    public void completeMutation(
            long userId,
            String actionKey,
            String idempotencyKey,
            Map<String, Object> response
    ) {
        int updated = jdbc.update("""
                UPDATE mission_mutation_idempotency
                SET completed=TRUE, response_json=?, updated_at=?
                WHERE user_id=? AND action_key=? AND idempotency_key=? AND completed=FALSE
                """, json(response), now(), userId, actionKey, idempotencyKey);
        if (updated != 1) throw new IllegalStateException("持续任务幂等结果保存失败。");
    }

    public long createMission(
            long userId,
            Long projectId,
            String title,
            String goal,
            List<Object> completionCriteria,
            Map<String, Object> constraints,
            String templateId,
            Map<String, Object> notificationPolicy,
            Map<String, Object> quietHours,
            Map<String, Object> singleRunBudget,
            Map<String, Object> periodBudget,
            String periodType,
            LocalDateTime validFrom,
            LocalDateTime expiresAt
    ) {
        long id = nextId();
        LocalDateTime now = now();
        jdbc.update("""
                INSERT INTO agent_mission
                  (id, user_id, project_id, title, goal, completion_criteria, constraints_json,
                   template_id, status, active_grant_version, notification_policy, quiet_hours,
                   single_run_budget, period_budget, period_type, valid_from, expires_at,
                   next_run_at, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', NULL, ?, ?, ?, ?, ?, ?, ?, NULL, 0, ?, ?)
                """, id, userId, projectId, title, goal, json(completionCriteria), json(constraints),
                templateId, json(notificationPolicy), json(quietHours), json(singleRunBudget),
                json(periodBudget), periodType, validFrom, expiresAt, now, now);
        return id;
    }

    public Optional<MissionRecord> findMission(long missionId, long userId) {
        List<MissionRecord> rows = jdbc.query("""
                SELECT id, user_id, project_id, title, goal, completion_criteria, constraints_json,
                       template_id, status, active_grant_version, notification_policy, quiet_hours,
                       single_run_budget, period_budget, period_type, valid_from, expires_at,
                       next_run_at, version, created_at, updated_at, archived_at
                FROM agent_mission
                WHERE id=? AND user_id=? AND archived_at IS NULL
                """, missionMapper(), missionId, userId);
        return rows.stream().findFirst();
    }

    public Optional<MissionRecord> findMission(long missionId) {
        List<MissionRecord> rows = jdbc.query("""
                SELECT id, user_id, project_id, title, goal, completion_criteria, constraints_json,
                       template_id, status, active_grant_version, notification_policy, quiet_hours,
                       single_run_budget, period_budget, period_type, valid_from, expires_at,
                       next_run_at, version, created_at, updated_at, archived_at
                FROM agent_mission
                WHERE id=? AND archived_at IS NULL
                """, missionMapper(), missionId);
        return rows.stream().findFirst();
    }

    public List<MissionRecord> listMissions(long userId, String status, int limit) {
        int bounded = Math.max(1, Math.min(limit, 100));
        if (status == null || status.isBlank()) {
            return jdbc.query("""
                    SELECT id, user_id, project_id, title, goal, completion_criteria, constraints_json,
                           template_id, status, active_grant_version, notification_policy, quiet_hours,
                           single_run_budget, period_budget, period_type, valid_from, expires_at,
                           next_run_at, version, created_at, updated_at, archived_at
                    FROM agent_mission
                    WHERE user_id=? AND archived_at IS NULL
                    ORDER BY updated_at DESC LIMIT ?
                    """, missionMapper(), userId, bounded);
        }
        return jdbc.query("""
                SELECT id, user_id, project_id, title, goal, completion_criteria, constraints_json,
                       template_id, status, active_grant_version, notification_policy, quiet_hours,
                       single_run_budget, period_budget, period_type, valid_from, expires_at,
                       next_run_at, version, created_at, updated_at, archived_at
                FROM agent_mission
                WHERE user_id=? AND status=? AND archived_at IS NULL
                ORDER BY updated_at DESC LIMIT ?
                """, missionMapper(), userId, status, bounded);
    }

    public long countActiveMissions() {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_mission
                WHERE status='ACTIVE' AND archived_at IS NULL
                """, Long.class);
        return count == null ? 0 : count;
    }

    public boolean transitionMission(
            long missionId,
            long userId,
            long expectedVersion,
            String from,
            String to
    ) {
        return jdbc.update("""
                UPDATE agent_mission
                SET status=?, version=version+1, updated_at=?
                WHERE id=? AND user_id=? AND version=? AND status=? AND archived_at IS NULL
                """, to, now(), missionId, userId, expectedVersion, from) == 1;
    }

    public boolean updateMissionMetadata(
            long missionId,
            long userId,
            long expectedVersion,
            String title,
            String goal,
            List<Object> completionCriteria,
            Map<String, Object> constraints,
            Map<String, Object> notificationPolicy,
            Map<String, Object> quietHours
    ) {
        return jdbc.update("""
                UPDATE agent_mission
                SET title=?, goal=?, completion_criteria=?, constraints_json=?,
                    notification_policy=?, quiet_hours=?, version=version+1, updated_at=?
                WHERE id=? AND user_id=? AND version=?
                  AND status IN ('DRAFT', 'SHADOW', 'ACTIVE', 'PAUSED')
                  AND archived_at IS NULL
                """, title, goal, json(completionCriteria), json(constraints),
                json(notificationPolicy), json(quietHours), now(),
                missionId, userId, expectedVersion) == 1;
    }

    public boolean activateMission(
            long missionId,
            long userId,
            long expectedVersion,
            int grantVersion,
            LocalDateTime nextRunAt
    ) {
        return jdbc.update("""
                UPDATE agent_mission
                SET status='ACTIVE', active_grant_version=?, next_run_at=?,
                    version=version+1, updated_at=?
                WHERE id=? AND user_id=? AND version=? AND status IN ('AWAITING_APPROVAL', 'PAUSED')
                  AND archived_at IS NULL
                """, grantVersion, nextRunAt, now(), missionId, userId, expectedVersion) == 1;
    }

    public boolean replaceActiveGrant(
            long missionId,
            long userId,
            long expectedVersion,
            int grantVersion
    ) {
        return jdbc.update("""
                UPDATE agent_mission
                SET active_grant_version=?, version=version+1, updated_at=?
                WHERE id=? AND user_id=? AND version=? AND status IN ('ACTIVE', 'PAUSED')
                  AND archived_at IS NULL
                """, grantVersion, now(), missionId, userId, expectedVersion) == 1;
    }

    public boolean revokeMission(long missionId, long userId, long expectedVersion) {
        return jdbc.update("""
                UPDATE agent_mission
                SET status='REVOKED', next_run_at=NULL, version=version+1, updated_at=?
                WHERE id=? AND user_id=? AND version=? AND status NOT IN ('REVOKED', 'ARCHIVED')
                  AND archived_at IS NULL
                """, now(), missionId, userId, expectedVersion) == 1;
    }

    public void expireDueMissions() {
        jdbc.update("""
                UPDATE agent_mission
                SET status='EXPIRED', next_run_at=NULL, version=version+1, updated_at=?
                WHERE status IN ('ACTIVE', 'PAUSED') AND expires_at<=? AND archived_at IS NULL
                """, now(), now());
        jdbc.update("""
                UPDATE delegation_grant g
                JOIN agent_mission m ON m.id=g.mission_id
                SET g.status='EXPIRED'
                WHERE g.status='ACTIVE' AND (g.expires_at<=? OR m.status='EXPIRED')
                """, now());
        jdbc.update("""
                UPDATE agent_mission m
                JOIN delegation_grant g
                  ON g.mission_id=m.id AND g.version=m.active_grant_version
                SET m.status='PAUSED', m.next_run_at=NULL,
                    m.version=m.version+1, m.updated_at=?
                WHERE m.status='ACTIVE' AND m.expires_at>?
                  AND (g.status<>'ACTIVE' OR g.expires_at<=?)
                """, now(), now(), now());
    }

    public int nextGrantVersion(long missionId) {
        Integer result = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) + 1 FROM delegation_grant WHERE mission_id=?",
                Integer.class,
                missionId
        );
        return result == null ? 1 : result;
    }

    public long createGrant(
            long missionId,
            int version,
            String summary,
            List<Object> rules,
            Map<String, Object> budgetPolicy,
            LocalDateTime validFrom,
            LocalDateTime expiresAt,
            String contentHash
    ) {
        long id = nextId();
        jdbc.update("""
                INSERT INTO delegation_grant
                  (id, mission_id, version, status, summary, rules, budget_policy,
                   valid_from, expires_at, approved_by, approved_at, content_hash, created_at)
                VALUES (?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, NULL, NULL, ?, ?)
                """, id, missionId, version, summary, json(rules), json(budgetPolicy),
                validFrom, expiresAt, contentHash, now());
        return id;
    }

    public Optional<GrantRecord> findGrant(long missionId, int version) {
        List<GrantRecord> rows = jdbc.query("""
                SELECT id, mission_id, version, status, summary, rules, budget_policy,
                       valid_from, expires_at, approved_by, approved_at, content_hash, created_at
                FROM delegation_grant WHERE mission_id=? AND version=?
                """, grantMapper(), missionId, version);
        return rows.stream().findFirst();
    }

    public Optional<GrantRecord> findActiveGrant(long missionId) {
        List<GrantRecord> rows = jdbc.query("""
                SELECT id, mission_id, version, status, summary, rules, budget_policy,
                       valid_from, expires_at, approved_by, approved_at, content_hash, created_at
                FROM delegation_grant WHERE mission_id=? AND status='ACTIVE'
                ORDER BY version DESC LIMIT 1
                """, grantMapper(), missionId);
        return rows.stream().findFirst();
    }

    public List<GrantRecord> listGrants(long missionId) {
        return jdbc.query("""
                SELECT id, mission_id, version, status, summary, rules, budget_policy,
                       valid_from, expires_at, approved_by, approved_at, content_hash, created_at
                FROM delegation_grant WHERE mission_id=? ORDER BY version DESC
                """, grantMapper(), missionId);
    }

    @Transactional
    public void approveGrant(long missionId, int version, long userId) {
        jdbc.update("""
                UPDATE delegation_grant
                SET status='SUPERSEDED'
                WHERE mission_id=? AND status='ACTIVE' AND version<>?
                """, missionId, version);
        int updated = jdbc.update("""
                UPDATE delegation_grant
                SET status='ACTIVE', approved_by=?, approved_at=?
                WHERE mission_id=? AND version=? AND status IN ('DRAFT', 'PENDING_APPROVAL')
                """, userId, now(), missionId, version);
        if (updated != 1) throw new IllegalStateException("授权版本已变化，请刷新后重试。");
    }

    public void markGrantPending(long missionId, int version) {
        jdbc.update("""
                UPDATE delegation_grant SET status='PENDING_APPROVAL'
                WHERE mission_id=? AND version=? AND status='DRAFT'
                """, missionId, version);
    }

    public void revokeGrants(long missionId) {
        jdbc.update("""
                UPDATE delegation_grant SET status='REVOKED'
                WHERE mission_id=? AND status IN ('ACTIVE', 'PENDING_APPROVAL', 'DRAFT')
                """, missionId);
    }

    public boolean revokeGrant(long missionId, int version) {
        return jdbc.update("""
                UPDATE delegation_grant SET status='REVOKED'
                WHERE mission_id=? AND version=?
                  AND status IN ('ACTIVE', 'PENDING_APPROVAL', 'DRAFT')
                """, missionId, version) == 1;
    }

    public boolean clearActiveGrantAndPause(
            long missionId,
            long userId,
            long expectedVersion,
            int grantVersion
    ) {
        return jdbc.update("""
                UPDATE agent_mission
                SET status='PAUSED', active_grant_version=NULL, next_run_at=NULL,
                    version=version+1, updated_at=?
                WHERE id=? AND user_id=? AND version=? AND active_grant_version=?
                  AND status IN ('ACTIVE', 'PAUSED') AND archived_at IS NULL
                """, now(), missionId, userId, expectedVersion, grantVersion) == 1;
    }

    public boolean revertApprovalToShadow(
            long missionId,
            long userId,
            long expectedVersion
    ) {
        return jdbc.update("""
                UPDATE agent_mission
                SET status='SHADOW', version=version+1, updated_at=?
                WHERE id=? AND user_id=? AND version=? AND status='AWAITING_APPROVAL'
                  AND active_grant_version IS NULL AND archived_at IS NULL
                """, now(), missionId, userId, expectedVersion) == 1;
    }

    public boolean touchMission(long missionId, long userId, long expectedVersion) {
        return jdbc.update("""
                UPDATE agent_mission SET version=version+1, updated_at=?
                WHERE id=? AND user_id=? AND version=? AND archived_at IS NULL
                """, now(), missionId, userId, expectedVersion) == 1;
    }

    public long createTrigger(
            long missionId,
            String type,
            Map<String, Object> config,
            String timezone,
            String misfirePolicy,
            boolean enabled,
            LocalDateTime nextFireAt
    ) {
        long id = nextId();
        LocalDateTime now = now();
        jdbc.update("""
                INSERT INTO mission_trigger
                  (id, mission_id, type, config, timezone, misfire_policy, enabled,
                   next_fire_at, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                """, id, missionId, type, json(config), timezone, misfirePolicy,
                enabled, nextFireAt, now, now);
        return id;
    }

    public List<TriggerRecord> listTriggers(long missionId) {
        return jdbc.query("""
                SELECT id, mission_id, type, config, timezone, misfire_policy, enabled,
                       next_fire_at, version, created_at, updated_at
                FROM mission_trigger WHERE mission_id=? ORDER BY created_at ASC
                """, triggerMapper(), missionId);
    }

    public Optional<TriggerRecord> findTrigger(long triggerId, long missionId) {
        List<TriggerRecord> rows = jdbc.query("""
                SELECT id, mission_id, type, config, timezone, misfire_policy, enabled,
                       next_fire_at, version, created_at, updated_at
                FROM mission_trigger WHERE id=? AND mission_id=?
                """, triggerMapper(), triggerId, missionId);
        return rows.stream().findFirst();
    }

    public List<TriggerRecord> findDueTriggers(int limit) {
        return jdbc.query("""
                SELECT t.id, t.mission_id, t.type, t.config, t.timezone, t.misfire_policy,
                       t.enabled, t.next_fire_at, t.version, t.created_at, t.updated_at
                FROM mission_trigger t
                JOIN agent_mission m ON m.id=t.mission_id
                JOIN delegation_grant g
                  ON g.mission_id=m.id AND g.version=m.active_grant_version
                WHERE t.enabled=TRUE AND t.next_fire_at IS NOT NULL AND t.next_fire_at<=?
                  AND m.status='ACTIVE' AND m.expires_at>?
                  AND g.status='ACTIVE' AND g.expires_at>?
                ORDER BY t.next_fire_at ASC LIMIT ?
                """, triggerMapper(), now(), now(), now(), Math.max(1, Math.min(limit, 100)));
    }

    public List<TriggerRecord> findDueTriggersWithoutValidGrant(int limit) {
        return jdbc.query("""
                SELECT t.id, t.mission_id, t.type, t.config, t.timezone, t.misfire_policy,
                       t.enabled, t.next_fire_at, t.version, t.created_at, t.updated_at
                FROM mission_trigger t
                JOIN agent_mission m ON m.id=t.mission_id
                LEFT JOIN delegation_grant g
                  ON g.mission_id=m.id AND g.version=m.active_grant_version
                WHERE t.enabled=TRUE AND t.next_fire_at IS NOT NULL AND t.next_fire_at<=?
                  AND m.status='ACTIVE' AND m.expires_at>?
                  AND (g.id IS NULL OR g.status<>'ACTIVE' OR g.expires_at<=?)
                ORDER BY t.next_fire_at ASC LIMIT ?
                """, triggerMapper(), now(), now(), now(), Math.max(1, Math.min(limit, 100)));
    }

    public boolean updateTriggerNextFire(
            long triggerId,
            long expectedVersion,
            LocalDateTime nextFireAt
    ) {
        return jdbc.update("""
                UPDATE mission_trigger
                SET next_fire_at=?, version=version+1, updated_at=?
                WHERE id=? AND version=? AND enabled=TRUE
                """, nextFireAt, now(), triggerId, expectedVersion) == 1;
    }

    public boolean updateTrigger(
            long triggerId,
            long missionId,
            long expectedVersion,
            String type,
            Map<String, Object> config,
            String timezone,
            String misfirePolicy,
            boolean enabled,
            LocalDateTime nextFireAt
    ) {
        return jdbc.update("""
                UPDATE mission_trigger
                SET type=?, config=?, timezone=?, misfire_policy=?, enabled=?,
                    next_fire_at=?, version=version+1, updated_at=?
                WHERE id=? AND mission_id=? AND version=?
                """, type, json(config), timezone, misfirePolicy, enabled,
                nextFireAt, now(), triggerId, missionId, expectedVersion) == 1;
    }

    public boolean disableTrigger(
            long triggerId,
            long missionId,
            long expectedVersion
    ) {
        return jdbc.update("""
                UPDATE mission_trigger
                SET enabled=FALSE, next_fire_at=NULL, version=version+1, updated_at=?
                WHERE id=? AND mission_id=? AND version=? AND enabled=TRUE
                """, now(), triggerId, missionId, expectedVersion) == 1;
    }

    public void updateMissionNextRun(long missionId, LocalDateTime nextRunAt) {
        jdbc.update("""
                UPDATE agent_mission SET next_run_at=?, updated_at=? WHERE id=?
                """, nextRunAt, now(), missionId);
    }

    public Optional<OccurrenceRecord> createOccurrence(
            long missionId,
            long triggerId,
            LocalDateTime scheduledFor,
            Map<String, Object> payload,
            String idempotencyKey
    ) {
        long id = nextId();
        try {
            jdbc.update("""
                    INSERT INTO mission_trigger_occurrence
                      (id, mission_id, trigger_id, scheduled_for, trigger_payload_summary,
                       status, agent_task_id, lease_owner, lease_expires_at, idempotency_key,
                       created_at, completed_at)
                    VALUES (?, ?, ?, ?, ?, 'PENDING', NULL, NULL, NULL, ?, ?, NULL)
                    """, id, missionId, triggerId, scheduledFor, json(payload), idempotencyKey, now());
            return findOccurrence(id);
        } catch (DuplicateKeyException ignored) {
            return findOccurrenceByKey(idempotencyKey);
        }
    }

    public Optional<OccurrenceRecord> findOccurrence(long occurrenceId) {
        List<OccurrenceRecord> rows = jdbc.query("""
                SELECT id, mission_id, trigger_id, scheduled_for, trigger_payload_summary,
                       status, agent_task_id, lease_owner, lease_expires_at,
                       idempotency_key, created_at, completed_at
                FROM mission_trigger_occurrence WHERE id=?
                """, occurrenceMapper(), occurrenceId);
        return rows.stream().findFirst();
    }

    public Optional<OccurrenceRecord> findOccurrenceByKey(String idempotencyKey) {
        List<OccurrenceRecord> rows = jdbc.query("""
                SELECT id, mission_id, trigger_id, scheduled_for, trigger_payload_summary,
                       status, agent_task_id, lease_owner, lease_expires_at,
                       idempotency_key, created_at, completed_at
                FROM mission_trigger_occurrence WHERE idempotency_key=?
                """, occurrenceMapper(), idempotencyKey);
        return rows.stream().findFirst();
    }

    public List<OccurrenceRecord> listOccurrences(long missionId, int limit) {
        return jdbc.query("""
                SELECT id, mission_id, trigger_id, scheduled_for, trigger_payload_summary,
                       status, agent_task_id, lease_owner, lease_expires_at,
                       idempotency_key, created_at, completed_at
                FROM mission_trigger_occurrence WHERE mission_id=?
                ORDER BY scheduled_for DESC LIMIT ?
                """, occurrenceMapper(), missionId, Math.max(1, Math.min(limit, 200)));
    }

    public boolean claimOccurrence(long occurrenceId, String owner, int leaseSeconds) {
        return jdbc.update("""
                UPDATE mission_trigger_occurrence
                SET status='CLAIMED', lease_owner=?, lease_expires_at=DATE_ADD(?, INTERVAL ? SECOND)
                WHERE id=? AND (
                  status='PENDING' OR (status='CLAIMED' AND lease_expires_at<?)
                )
                """, owner, now(), Math.max(5, leaseSeconds), occurrenceId, now()) == 1;
    }

    public void attachTaskToOccurrence(long occurrenceId, long taskId) {
        jdbc.update("""
                UPDATE mission_trigger_occurrence
                SET status='TASK_CREATED', agent_task_id=?, lease_owner=NULL, lease_expires_at=NULL
                WHERE id=? AND status IN ('PENDING', 'CLAIMED', 'TASK_CREATED')
                """, taskId, occurrenceId);
    }

    public void markOccurrenceFailed(long occurrenceId) {
        jdbc.update("""
                UPDATE mission_trigger_occurrence
                SET status='FAILED', lease_owner=NULL, lease_expires_at=NULL, completed_at=?
                WHERE id=? AND status<>'COMPLETED'
                """, now(), occurrenceId);
    }

    public void markOccurrenceSkipped(long occurrenceId) {
        jdbc.update("""
                UPDATE mission_trigger_occurrence
                SET status='SKIPPED', lease_owner=NULL, lease_expires_at=NULL, completed_at=?
                WHERE id=? AND status IN ('PENDING', 'CLAIMED')
                """, now(), occurrenceId);
    }

    public void completeTerminalTaskOccurrences() {
        jdbc.update("""
                UPDATE mission_trigger_occurrence o
                JOIN agent_task t ON t.id=o.agent_task_id
                SET o.status='COMPLETED', o.completed_at=?,
                    o.lease_owner=NULL, o.lease_expires_at=NULL
                WHERE o.status='TASK_CREATED'
                  AND t.status IN ('COMPLETED', 'PARTIALLY_COMPLETED', 'FAILED', 'CANCELLED')
                """, now());
    }

    public List<OccurrenceCompletionRecord> findTerminalTaskOccurrences() {
        return jdbc.query("""
                SELECT o.id, o.mission_id, o.agent_task_id, t.status,
                       t.template_id, t.created_at, t.updated_at
                FROM mission_trigger_occurrence o
                JOIN agent_task t ON t.id=o.agent_task_id
                WHERE o.status='TASK_CREATED'
                  AND t.status IN ('COMPLETED','PARTIALLY_COMPLETED','FAILED','CANCELLED')
                ORDER BY o.created_at
                """, (rs, rowNum) -> new OccurrenceCompletionRecord(
                rs.getLong(1),
                rs.getLong(2),
                rs.getLong(3),
                rs.getString(4),
                rs.getString(5),
                rs.getObject(6, LocalDateTime.class),
                rs.getObject(7, LocalDateTime.class)
        ));
    }

    @Transactional
    public long appendMissionEvent(
            long missionId,
            String eventType,
            String status,
            Map<String, Object> payload
    ) {
        Long currentSequence = jdbc.queryForObject("""
                SELECT event_sequence
                FROM agent_mission
                WHERE id=?
                FOR UPDATE
                """, Long.class, missionId);
        long sequence = currentSequence == null ? 1 : currentSequence + 1;
        int advanced = jdbc.update("""
                UPDATE agent_mission
                SET event_sequence=?
                WHERE id=? AND event_sequence=?
                """, sequence, missionId, currentSequence == null ? 0 : currentSequence);
        if (advanced != 1) {
            throw new IllegalStateException("持续任务事件序号分配失败。");
        }
        long id = nextId();
        jdbc.update("""
                INSERT INTO mission_event
                  (id, mission_id, sequence_no, event_type, status, payload_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, missionId, sequence,
                eventType, status, json(payload), now());
        return sequence;
    }

    public List<MissionEventRecord> listMissionEvents(long missionId, long afterSequence, int limit) {
        return jdbc.query("""
                SELECT sequence_no, event_type, status, payload_json, created_at
                FROM mission_event
                WHERE mission_id=? AND sequence_no>?
                ORDER BY sequence_no LIMIT ?
                """, (rs, rowNum) -> new MissionEventRecord(
                rs.getLong(1),
                rs.getString(2),
                rs.getString(3),
                readMap(rs.getString(4)),
                rs.getObject(5, LocalDateTime.class)
        ), missionId, Math.max(0, afterSequence), Math.max(1, Math.min(limit, 1000)));
    }

    public void linkDelegatedTask(
            long taskId,
            long missionId,
            long occurrenceId,
            int grantVersion
    ) {
        jdbc.update("""
                UPDATE agent_task
                SET mission_id=?, trigger_occurrence_id=?, delegation_grant_version=?,
                    execution_mode='DELEGATED', updated_at=?
                WHERE id=? AND mission_id IS NULL
                """, missionId, occurrenceId, grantVersion, now(), taskId);
    }

    public Optional<TaskDelegationRecord> findTaskDelegation(long taskId) {
        List<TaskDelegationRecord> rows = jdbc.query("""
                SELECT t.id, t.user_id, t.mission_id, t.trigger_occurrence_id,
                       t.delegation_grant_version, t.execution_mode,
                       m.status, m.active_grant_version, m.valid_from, m.expires_at,
                       m.period_budget, m.period_type
                FROM agent_task t
                LEFT JOIN agent_mission m ON m.id=t.mission_id
                WHERE t.id=?
                """, (rs, rowNum) -> new TaskDelegationRecord(
                rs.getLong("id"),
                rs.getLong("user_id"),
                nullableLong(rs, "mission_id"),
                nullableLong(rs, "trigger_occurrence_id"),
                nullableInteger(rs, "delegation_grant_version"),
                rs.getString("execution_mode"),
                rs.getString("status"),
                nullableInteger(rs, "active_grant_version"),
                rs.getObject("valid_from", LocalDateTime.class),
                rs.getObject("expires_at", LocalDateTime.class),
                readMap(rs.getString("period_budget")),
                rs.getString("period_type")
        ), taskId);
        return rows.stream().findFirst();
    }

    public BudgetUsageRecord aggregateMissionUsage(
            long missionId,
            LocalDateTime periodStart,
            LocalDateTime periodEnd
    ) {
        return jdbc.queryForObject("""
                SELECT 0,
                       COALESCE(SUM(l.tool_calls), 0),
                       COALESCE(SUM(l.model_tokens), 0),
                       COALESCE(SUM(l.cost_micros), 0),
                       COALESCE(SUM(l.elapsed_millis), 0)
                FROM agent_budget_ledger l
                JOIN agent_task_run r ON r.id=l.task_run_id
                JOIN agent_task t ON t.id=r.agent_task_id
                WHERE t.mission_id=? AND l.created_at>=? AND l.created_at<?
                """, (rs, rowNum) -> new BudgetUsageRecord(
                rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)
        ), missionId, periodStart, periodEnd);
    }

    public long countTaskToolAttempts(long taskId, String toolRef) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM agent_step_run r
                JOIN agent_plan_step s ON s.id=r.plan_step_id
                JOIN agent_task_run tr ON tr.id=r.task_run_id
                WHERE tr.agent_task_id=? AND s.tool_ref=?
                """, Long.class, taskId, toolRef);
        return count == null ? 0 : count;
    }

    public long createAuthorizationDecision(
            long userId,
            long missionId,
            long agentTaskId,
            Long stepRunId,
            int grantVersion,
            String toolRef,
            String operation,
            String resourceHash,
            String riskLevel,
            String reversibility,
            String decision,
            String reasonCode,
            String matchedRuleId
    ) {
        long id = nextId();
        jdbc.update("""
                INSERT INTO authorization_decision
                  (id, user_id, mission_id, agent_task_id, step_run_id, grant_version,
                   tool_ref, operation_name, resource_ref_hash, risk_level, reversibility,
                   decision, reason_code, matched_rule_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, userId, missionId, agentTaskId, stepRunId, grantVersion,
                toolRef, operation, resourceHash, riskLevel, reversibility,
                decision, reasonCode, matchedRuleId, now());
        return id;
    }

    public List<Map<String, Object>> listAuthorizationDecisions(long missionId, int limit) {
        return jdbc.query("""
                SELECT id, agent_task_id, step_run_id, grant_version, tool_ref, operation_name,
                       resource_ref_hash, risk_level, reversibility, decision, reason_code,
                       matched_rule_id, created_at
                FROM authorization_decision WHERE mission_id=?
                ORDER BY created_at DESC LIMIT ?
                """, (rs, rowNum) -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", String.valueOf(rs.getLong("id")));
            value.put("agentTaskId", String.valueOf(rs.getLong("agent_task_id")));
            value.put("stepRunId", stringOrNull(nullableLong(rs, "step_run_id")));
            value.put("grantVersion", rs.getInt("grant_version"));
            value.put("toolRef", rs.getString("tool_ref"));
            value.put("operation", rs.getString("operation_name"));
            value.put("resourceRefHash", rs.getString("resource_ref_hash"));
            value.put("riskLevel", rs.getString("risk_level"));
            value.put("reversibility", rs.getString("reversibility"));
            value.put("decision", rs.getString("decision"));
            value.put("reasonCode", rs.getString("reason_code"));
            value.put("matchedRuleId", rs.getString("matched_rule_id"));
            value.put("createdAt", iso(rs.getObject("created_at", LocalDateTime.class)));
            return value;
        }, missionId, Math.max(1, Math.min(limit, 500)));
    }

    public long openException(
            long userId,
            long missionId,
            Long agentTaskId,
            String type,
            String severity,
            String dedupeKey,
            String title,
            String summary,
            List<Object> evidenceRefs,
            List<Object> availableActions
    ) {
        List<Long> existing = jdbc.query("""
                SELECT id FROM mission_exception
                WHERE mission_id=? AND dedupe_key=? AND status IN ('OPEN', 'ACKNOWLEDGED')
                  AND created_at>=DATE_SUB(?, INTERVAL 24 HOUR)
                ORDER BY created_at DESC LIMIT 1
                """, (rs, rowNum) -> rs.getLong(1), missionId, dedupeKey, now());
        if (!existing.isEmpty()) {
            jdbc.update("""
                    UPDATE mission_exception SET summary=?, severity=?, available_actions=?
                    WHERE id=?
                    """, summary, severity, json(availableActions), existing.get(0));
            return existing.get(0);
        }
        long id = nextId();
        jdbc.update("""
                INSERT INTO mission_exception
                  (id, user_id, mission_id, agent_task_id, type, severity, dedupe_key,
                   title, summary, evidence_refs, available_actions, status, created_at, resolved_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, NULL)
                """, id, userId, missionId, agentTaskId, type, severity, dedupeKey,
                title, summary, json(evidenceRefs), json(availableActions), now());
        return id;
    }

    public List<ExceptionRecord> listExceptions(long userId, String status, int limit) {
        int bounded = Math.max(1, Math.min(limit, 200));
        if (status == null || status.isBlank()) {
            return jdbc.query("""
                    SELECT id, user_id, mission_id, agent_task_id, type, severity, dedupe_key,
                           title, summary, evidence_refs, available_actions, status, created_at, resolved_at
                    FROM mission_exception WHERE user_id=?
                    ORDER BY FIELD(status, 'OPEN', 'ACKNOWLEDGED', 'RESOLVED', 'DISMISSED'), created_at DESC
                    LIMIT ?
                    """, exceptionMapper(), userId, bounded);
        }
        return jdbc.query("""
                SELECT id, user_id, mission_id, agent_task_id, type, severity, dedupe_key,
                       title, summary, evidence_refs, available_actions, status, created_at, resolved_at
                FROM mission_exception WHERE user_id=? AND status=?
                ORDER BY created_at DESC LIMIT ?
                """, exceptionMapper(), userId, status, bounded);
    }

    public Optional<ExceptionRecord> findException(long exceptionId, long userId) {
        List<ExceptionRecord> rows = jdbc.query("""
                SELECT id, user_id, mission_id, agent_task_id, type, severity, dedupe_key,
                       title, summary, evidence_refs, available_actions, status, created_at, resolved_at
                FROM mission_exception WHERE id=? AND user_id=?
                """, exceptionMapper(), exceptionId, userId);
        return rows.stream().findFirst();
    }

    public boolean updateExceptionStatus(long exceptionId, long userId, String from, String to) {
        return jdbc.update("""
                UPDATE mission_exception
                SET status=?, resolved_at=CASE WHEN ? IN ('RESOLVED', 'DISMISSED') THEN ? ELSE resolved_at END
                WHERE id=? AND user_id=? AND status=?
                """, to, to, now(), exceptionId, userId, from) == 1;
    }

    public long createCompensation(
            long userId,
            long missionId,
            long taskId,
            long stepRunId,
            String toolRef,
            String operation,
            String resourceHash,
            Map<String, Object> precondition,
            String payloadRef,
            LocalDateTime expiresAt,
            String idempotencyKey
    ) {
        Optional<CompensationRecord> existing = findCompensationByStep(stepRunId);
        if (existing.isPresent()) return existing.get().id();
        long id = nextId();
        try {
            jdbc.update("""
                    INSERT INTO agent_compensation
                      (id, user_id, mission_id, agent_task_id, step_run_id, tool_ref,
                       operation_name, resource_ref_hash, precondition_snapshot,
                       compensation_payload_ref, expires_at, status, idempotency_key,
                       requested_at, completed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'AVAILABLE', ?, NULL, NULL)
                    """, id, userId, missionId, taskId, stepRunId, toolRef, operation,
                    resourceHash, json(precondition), payloadRef, expiresAt, idempotencyKey);
            return id;
        } catch (DuplicateKeyException ignored) {
            return findCompensationByStep(stepRunId).map(CompensationRecord::id).orElse(id);
        }
    }

    public Optional<CompensationRecord> findCompensationByStep(long stepRunId) {
        List<CompensationRecord> rows = jdbc.query("""
                SELECT id, user_id, mission_id, agent_task_id, step_run_id, tool_ref,
                       operation_name, resource_ref_hash, precondition_snapshot,
                       compensation_payload_ref, expires_at, status, idempotency_key,
                       requested_at, completed_at
                FROM agent_compensation WHERE step_run_id=?
                ORDER BY id DESC LIMIT 1
                """, compensationMapper(), stepRunId);
        return rows.stream().findFirst();
    }

    public Optional<CompensationRecord> findCompensation(long id, long userId) {
        List<CompensationRecord> rows = jdbc.query("""
                SELECT id, user_id, mission_id, agent_task_id, step_run_id, tool_ref,
                       operation_name, resource_ref_hash, precondition_snapshot,
                       compensation_payload_ref, expires_at, status, idempotency_key,
                       requested_at, completed_at
                FROM agent_compensation WHERE id=? AND user_id=?
                """, compensationMapper(), id, userId);
        return rows.stream().findFirst();
    }

    public boolean requestCompensation(long id, long userId) {
        return jdbc.update("""
                UPDATE agent_compensation
                SET status='REQUESTED', requested_at=?
                WHERE id=? AND user_id=? AND status='AVAILABLE' AND expires_at>?
                """, now(), id, userId, now()) == 1;
    }

    public void finishCompensation(long id, String status) {
        jdbc.update("""
                UPDATE agent_compensation SET status=?, completed_at=? WHERE id=?
                """, status, now(), id);
    }

    public List<Map<String, Object>> listRuns(long missionId, int limit) {
        return jdbc.query("""
                SELECT t.id, t.status, t.title, t.created_at, t.updated_at,
                       o.id AS occurrence_id, o.scheduled_for, o.status AS occurrence_status
                FROM agent_task t
                LEFT JOIN mission_trigger_occurrence o ON o.id=t.trigger_occurrence_id
                WHERE t.mission_id=?
                ORDER BY t.created_at DESC LIMIT ?
                """, (rs, rowNum) -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("agentTaskId", String.valueOf(rs.getLong("id")));
            value.put("status", rs.getString("status"));
            value.put("title", rs.getString("title"));
            value.put("createdAt", iso(rs.getObject("created_at", LocalDateTime.class)));
            value.put("updatedAt", iso(rs.getObject("updated_at", LocalDateTime.class)));
            Long occurrenceId = nullableLong(rs, "occurrence_id");
            value.put("occurrenceId", stringOrNull(occurrenceId));
            value.put("scheduledFor", iso(rs.getObject("scheduled_for", LocalDateTime.class)));
            value.put("occurrenceStatus", rs.getString("occurrence_status"));
            return value;
        }, missionId, Math.max(1, Math.min(limit, 200)));
    }

    private org.springframework.jdbc.core.RowMapper<MissionRecord> missionMapper() {
        return (rs, rowNum) -> new MissionRecord(
                rs.getLong("id"), rs.getLong("user_id"), nullableLong(rs, "project_id"),
                rs.getString("title"), rs.getString("goal"),
                readList(rs.getString("completion_criteria")),
                readMap(rs.getString("constraints_json")),
                rs.getString("template_id"), rs.getString("status"),
                nullableInteger(rs, "active_grant_version"),
                readMap(rs.getString("notification_policy")),
                readMap(rs.getString("quiet_hours")),
                readMap(rs.getString("single_run_budget")),
                readMap(rs.getString("period_budget")),
                rs.getString("period_type"),
                rs.getObject("valid_from", LocalDateTime.class),
                rs.getObject("expires_at", LocalDateTime.class),
                rs.getObject("next_run_at", LocalDateTime.class),
                rs.getLong("version"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class),
                rs.getObject("archived_at", LocalDateTime.class)
        );
    }

    private org.springframework.jdbc.core.RowMapper<GrantRecord> grantMapper() {
        return (rs, rowNum) -> new GrantRecord(
                rs.getLong("id"), rs.getLong("mission_id"), rs.getInt("version"),
                rs.getString("status"), rs.getString("summary"),
                readList(rs.getString("rules")), readMap(rs.getString("budget_policy")),
                rs.getObject("valid_from", LocalDateTime.class),
                rs.getObject("expires_at", LocalDateTime.class),
                nullableLong(rs, "approved_by"),
                rs.getObject("approved_at", LocalDateTime.class),
                rs.getString("content_hash"),
                rs.getObject("created_at", LocalDateTime.class)
        );
    }

    private org.springframework.jdbc.core.RowMapper<TriggerRecord> triggerMapper() {
        return (rs, rowNum) -> new TriggerRecord(
                rs.getLong("id"), rs.getLong("mission_id"), rs.getString("type"),
                readMap(rs.getString("config")), rs.getString("timezone"),
                rs.getString("misfire_policy"), rs.getBoolean("enabled"),
                rs.getObject("next_fire_at", LocalDateTime.class), rs.getLong("version"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)
        );
    }

    private org.springframework.jdbc.core.RowMapper<OccurrenceRecord> occurrenceMapper() {
        return (rs, rowNum) -> new OccurrenceRecord(
                rs.getLong("id"), rs.getLong("mission_id"), rs.getLong("trigger_id"),
                rs.getObject("scheduled_for", LocalDateTime.class),
                readMap(rs.getString("trigger_payload_summary")),
                rs.getString("status"), nullableLong(rs, "agent_task_id"),
                rs.getString("lease_owner"), rs.getObject("lease_expires_at", LocalDateTime.class),
                rs.getString("idempotency_key"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("completed_at", LocalDateTime.class)
        );
    }

    private org.springframework.jdbc.core.RowMapper<ExceptionRecord> exceptionMapper() {
        return (rs, rowNum) -> new ExceptionRecord(
                rs.getLong("id"), rs.getLong("user_id"), rs.getLong("mission_id"),
                nullableLong(rs, "agent_task_id"), rs.getString("type"),
                rs.getString("severity"), rs.getString("dedupe_key"),
                rs.getString("title"), rs.getString("summary"),
                readList(rs.getString("evidence_refs")),
                readList(rs.getString("available_actions")),
                rs.getString("status"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("resolved_at", LocalDateTime.class)
        );
    }

    private org.springframework.jdbc.core.RowMapper<CompensationRecord> compensationMapper() {
        return (rs, rowNum) -> new CompensationRecord(
                rs.getLong("id"), rs.getLong("user_id"), rs.getLong("mission_id"),
                rs.getLong("agent_task_id"), rs.getLong("step_run_id"),
                rs.getString("tool_ref"), rs.getString("operation_name"),
                rs.getString("resource_ref_hash"),
                readMap(rs.getString("precondition_snapshot")),
                rs.getString("compensation_payload_ref"),
                rs.getObject("expires_at", LocalDateTime.class),
                rs.getString("status"), rs.getString("idempotency_key"),
                rs.getObject("requested_at", LocalDateTime.class),
                rs.getObject("completed_at", LocalDateTime.class)
        );
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exc) {
            throw new IllegalArgumentException("V6 JSON 序列化失败。", exc);
        }
    }

    private Map<String, Object> readMap(String value) {
        if (value == null || value.isBlank()) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (JsonProcessingException exc) {
            throw new IllegalStateException("V6 JSON 数据损坏。", exc);
        }
    }

    private List<Object> readList(String value) {
        if (value == null || value.isBlank()) return new ArrayList<>();
        try {
            return objectMapper.readValue(value, LIST_TYPE);
        } catch (JsonProcessingException exc) {
            throw new IllegalStateException("V6 JSON 数据损坏。", exc);
        }
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static String stringOrNull(Long value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String iso(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC).toString();
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    public record MissionRecord(
            long id,
            long userId,
            Long projectId,
            String title,
            String goal,
            List<Object> completionCriteria,
            Map<String, Object> constraints,
            String templateId,
            String status,
            Integer activeGrantVersion,
            Map<String, Object> notificationPolicy,
            Map<String, Object> quietHours,
            Map<String, Object> singleRunBudget,
            Map<String, Object> periodBudget,
            String periodType,
            LocalDateTime validFrom,
            LocalDateTime expiresAt,
            LocalDateTime nextRunAt,
            long version,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime archivedAt
    ) { }

    public record GrantRecord(
            long id,
            long missionId,
            int version,
            String status,
            String summary,
            List<Object> rules,
            Map<String, Object> budgetPolicy,
            LocalDateTime validFrom,
            LocalDateTime expiresAt,
            Long approvedBy,
            LocalDateTime approvedAt,
            String contentHash,
            LocalDateTime createdAt
    ) { }

    public record TriggerRecord(
            long id,
            long missionId,
            String type,
            Map<String, Object> config,
            String timezone,
            String misfirePolicy,
            boolean enabled,
            LocalDateTime nextFireAt,
            long version,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) { }

    public record OccurrenceRecord(
            long id,
            long missionId,
            long triggerId,
            LocalDateTime scheduledFor,
            Map<String, Object> payload,
            String status,
            Long agentTaskId,
            String leaseOwner,
            LocalDateTime leaseExpiresAt,
            String idempotencyKey,
            LocalDateTime createdAt,
            LocalDateTime completedAt
    ) { }

    public record TaskDelegationRecord(
            long taskId,
            long userId,
            Long missionId,
            Long occurrenceId,
            Integer snapshotGrantVersion,
            String executionMode,
            String missionStatus,
            Integer activeGrantVersion,
            LocalDateTime validFrom,
            LocalDateTime expiresAt,
            Map<String, Object> periodBudget,
            String periodType
    ) {
        public boolean delegated() {
            return missionId != null && "DELEGATED".equals(executionMode);
        }
    }

    public record BudgetUsageRecord(
            long completedSteps,
            long toolCalls,
            long modelTokens,
            long costMicros,
            long elapsedMillis
    ) { }

    public record ExceptionRecord(
            long id,
            long userId,
            long missionId,
            Long agentTaskId,
            String type,
            String severity,
            String dedupeKey,
            String title,
            String summary,
            List<Object> evidenceRefs,
            List<Object> availableActions,
            String status,
            LocalDateTime createdAt,
            LocalDateTime resolvedAt
    ) { }

    public record CompensationRecord(
            long id,
            long userId,
            long missionId,
            long agentTaskId,
            long stepRunId,
            String toolRef,
            String operation,
            String resourceHash,
            Map<String, Object> precondition,
            String payloadRef,
            LocalDateTime expiresAt,
            String status,
            String idempotencyKey,
            LocalDateTime requestedAt,
            LocalDateTime completedAt
    ) { }

    public record OccurrenceCompletionRecord(
            long occurrenceId,
            long missionId,
            long agentTaskId,
            String status,
            String templateId,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) { }

    public record MissionEventRecord(
            long sequence,
            String eventType,
            String status,
            Map<String, Object> payload,
            LocalDateTime createdAt
    ) { }

    public record MutationIdempotencyRecord(
            boolean completed,
            Map<String, Object> response
    ) { }
}
