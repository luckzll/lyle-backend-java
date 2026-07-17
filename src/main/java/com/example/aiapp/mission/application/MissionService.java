package com.example.aiapp.mission.application;

import com.example.aiapp.agenttask.application.AgentTaskService;
import com.example.aiapp.mission.application.DelegationPolicyEngine.ActionIntent;
import com.example.aiapp.mission.application.DelegationPolicyEngine.PolicyDecision;
import com.example.aiapp.mission.config.MissionFeatureFlags;
import com.example.aiapp.mission.infrastructure.MissionRepository;
import com.example.aiapp.mission.infrastructure.MissionRepository.CompensationRecord;
import com.example.aiapp.mission.infrastructure.MissionRepository.BudgetUsageRecord;
import com.example.aiapp.mission.infrastructure.MissionRepository.ExceptionRecord;
import com.example.aiapp.mission.infrastructure.MissionRepository.GrantRecord;
import com.example.aiapp.mission.infrastructure.MissionRepository.MissionRecord;
import com.example.aiapp.mission.infrastructure.MissionRepository.MutationIdempotencyRecord;
import com.example.aiapp.mission.infrastructure.MissionRepository.OccurrenceRecord;
import com.example.aiapp.mission.infrastructure.MissionRepository.TriggerRecord;
import com.example.aiapp.mission.infrastructure.events.MissionEventPublisher;
import com.example.aiapp.mission.infrastructure.metrics.MissionMetrics;
import com.example.aiapp.mission.infrastructure.python.MissionAiClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class MissionService {

    private static final int MAX_VALID_DAYS = 30;
    private static final Set<String> MISSION_UPDATE_KEYS = Set.of(
            "expectedVersion", "title", "goal", "completionCriteria",
            "constraints", "notificationPolicy", "quietHours"
    );

    private final MissionRepository repository;
    private final MissionAiClient aiClient;
    private final DelegationPolicyEngine policyEngine;
    private final MissionScheduleCalculator schedules;
    private final MissionFeatureFlags featureFlags;
    private final AgentTaskService agentTaskService;
    private final ObjectMapper objectMapper;
    private final MissionEventPublisher missionEvents;
    private final MissionMetrics metrics;

    @Autowired
    public MissionService(
            MissionRepository repository,
            MissionAiClient aiClient,
            DelegationPolicyEngine policyEngine,
            MissionScheduleCalculator schedules,
            MissionFeatureFlags featureFlags,
            AgentTaskService agentTaskService,
            ObjectMapper objectMapper,
            MissionEventPublisher missionEvents,
            MissionMetrics metrics
    ) {
        this.repository = repository;
        this.aiClient = aiClient;
        this.policyEngine = policyEngine;
        this.schedules = schedules;
        this.featureFlags = featureFlags;
        this.agentTaskService = agentTaskService;
        this.objectMapper = objectMapper;
        this.missionEvents = missionEvents;
        this.metrics = metrics;
    }

    public MissionService(
            MissionRepository repository,
            MissionAiClient aiClient,
            DelegationPolicyEngine policyEngine,
            MissionScheduleCalculator schedules,
            MissionFeatureFlags featureFlags,
            AgentTaskService agentTaskService,
            ObjectMapper objectMapper
    ) {
        this(
                repository, aiClient, policyEngine, schedules, featureFlags,
                agentTaskService, objectMapper, null, null);
    }

    @Transactional
    public Map<String, Object> create(
            long userId,
            Map<String, Object> request,
            String idempotencyKey
    ) {
        requireControlPlane(userId);
        return idempotent(
                userId,
                "MISSION_CREATE",
                idempotencyKey,
                () -> createOnce(userId, request)
        );
    }

    @Transactional
    public Map<String, Object> mutateIdempotently(
            long userId,
            String actionKey,
            String idempotencyKey,
            Supplier<Map<String, Object>> operation
    ) {
        requireControlPlane(userId);
        if (actionKey == null || actionKey.isBlank() || actionKey.length() > 128) {
            throw badRequest("幂等动作标识无效。");
        }
        return idempotent(userId, actionKey, idempotencyKey, operation);
    }

    private Map<String, Object> createOnce(long userId, Map<String, Object> request) {
        requireControlPlane(userId);
        Map<String, Object> draft = aiClient.buildDraft(draftRequest(userId, request));
        String templateId = requiredText(draft, "templateId", 64);
        if (!featureFlags.allowsTemplate(userId, templateId)) {
            throw forbidden("该持续任务模板尚未对当前用户开放。");
        }
        String goal = requiredText(draft, "goal", 10_000);
        String title = textOr(draft.get("title"), goal.length() > 80 ? goal.substring(0, 80) : goal);
        Long projectId = nullableLong(draft.get("projectId"));
        List<Object> criteria = list(draft.get("completionCriteria"));
        if (criteria.isEmpty()) criteria = List.of("形成可验证结果并保留证据");
        Map<String, Object> constraints = map(draft.get("constraints"));
        Map<String, Object> notificationPolicy = map(draft.get("notificationPolicy"));
        Map<String, Object> quietHours = map(draft.get("quietHours"));
        Map<String, Object> singleRunBudget = map(draft.get("singleRunBudget"));
        Map<String, Object> periodBudget = map(draft.get("periodBudget"));
        String periodType = textOr(draft.get("periodType"), "WEEKLY").toUpperCase(Locale.ROOT);
        if (!List.of("DAILY", "WEEKLY", "MONTHLY").contains(periodType)) {
            throw badRequest("periodType 只支持 DAILY、WEEKLY 或 MONTHLY。");
        }
        int validityDays = Math.max(1, Math.min(integer(draft.get("validityDays"), 30), MAX_VALID_DAYS));
        LocalDateTime validFrom = now();
        LocalDateTime expiresAt = validFrom.plusDays(validityDays);

        List<Object> rules = list(draft.get("grantRules"));
        policyEngine.validateRules(rules);
        long missionId = repository.createMission(
                userId, projectId, title, goal, criteria, constraints, templateId,
                notificationPolicy, quietHours, singleRunBudget, periodBudget,
                periodType, validFrom, expiresAt
        );
        int grantVersion = repository.nextGrantVersion(missionId);
        repository.createGrant(
                missionId,
                grantVersion,
                textOr(draft.get("grantSummary"), "仅允许结构化规则列出的动作。"),
                rules,
                map(draft.get("budgetPolicy")),
                validFrom,
                expiresAt,
                grantHash(rules, map(draft.get("budgetPolicy")), validFrom, expiresAt)
        );
        List<Map<String, Object>> triggers = mapList(draft.get("triggers"));
        if (triggers.isEmpty()) {
            triggers = List.of(Map.of(
                    "type", "FIXED_TIME",
                    "config", Map.of("frequency", "DAILY", "time", "09:00"),
                    "timezone", textOr(request.get("timezone"), "Asia/Shanghai"),
                    "misfirePolicy", "RUN_ONCE"
            ));
        }
        for (Map<String, Object> trigger : triggers) {
            createTriggerInternal(missionId, trigger, true);
        }
        refreshMissionNextRun(missionId);
        publish(missionId, "mission.updated", Map.of(
                "status", "DRAFT",
                "summary", "持续任务草稿已创建。"
        ));
        publish(missionId, "grant.updated", Map.of(
                "status", "DRAFT",
                "grantVersion", grantVersion,
                "summary", "委托授权草稿已创建。"
        ));
        return detail(userId, missionId);
    }

    public Map<String, Object> generateDraft(long userId, Map<String, Object> request) {
        requireControlPlane(userId);
        return aiClient.buildDraft(draftRequest(userId, request));
    }

    public Map<String, Object> list(long userId, String status, int limit) {
        requireControlPlane(userId);
        return Map.of("items", repository.listMissions(userId, normalizeOptionalStatus(status), limit)
                .stream().map(this::missionListItem).toList());
    }

    public Map<String, Object> detail(long userId, long missionId) {
        requireControlPlane(userId);
        MissionRecord mission = requireMission(userId, missionId);
        Map<String, Object> result = missionMap(mission);
        result.put("grants", repository.listGrants(missionId).stream().map(this::grantMap).toList());
        result.put("triggers", repository.listTriggers(missionId).stream().map(this::triggerMap).toList());
        result.put("occurrences", repository.listOccurrences(missionId, 20).stream().map(this::occurrenceMap).toList());
        result.put("recentRuns", repository.listRuns(missionId, 20));
        result.put("recentDecisions", repository.listAuthorizationDecisions(missionId, 30));
        result.put("periodUsage", periodUsage(mission));
        result.put("availableActions", availableActions(mission));
        result.put("shadowOnly", featureFlags.shadowOnly());
        return result;
    }

    @Transactional
    public Map<String, Object> update(
            long userId,
            long missionId,
            long expectedVersion,
            Map<String, Object> request
    ) {
        MissionRecord mission = requireMission(userId, missionId);
        requireVersion(mission, expectedVersion);
        Set<String> unknown = new LinkedHashSet<>(request.keySet());
        unknown.removeAll(MISSION_UPDATE_KEYS);
        if (!unknown.isEmpty()) throw badRequest("持续任务更新包含未知字段：" + unknown);
        String title = request.containsKey("title")
                ? requiredText(request, "title", 255)
                : mission.title();
        String goal = request.containsKey("goal")
                ? requiredText(request, "goal", 10_000)
                : mission.goal();
        if (goal.length() < 5) throw badRequest("goal 至少需要 5 个字符。");
        List<Object> criteria = request.containsKey("completionCriteria")
                ? list(request.get("completionCriteria"))
                : mission.completionCriteria();
        if (criteria.isEmpty() || criteria.size() > 20) {
            throw badRequest("completionCriteria 必须为 1～20 条。");
        }
        Map<String, Object> constraints = request.containsKey("constraints")
                ? map(request.get("constraints"))
                : mission.constraints();
        Map<String, Object> notification = request.containsKey("notificationPolicy")
                ? map(request.get("notificationPolicy"))
                : mission.notificationPolicy();
        Map<String, Object> quietHours = request.containsKey("quietHours")
                ? map(request.get("quietHours"))
                : mission.quietHours();
        if (!repository.updateMissionMetadata(
                missionId, userId, expectedVersion, title, goal, criteria,
                constraints, notification, quietHours)) {
            throw conflict("持续任务已变化或当前状态不可编辑。");
        }
        publish(missionId, "mission.updated", Map.of(
                "status", mission.status(),
                "summary", "持续任务定义已更新。"
        ));
        return detail(userId, missionId);
    }

    public Map<String, Object> shadow(
            long userId,
            long missionId,
            long expectedVersion,
            Map<String, Object> request
    ) {
        requireControlPlane(userId);
        MissionRecord mission = requireMission(userId, missionId);
        requireVersion(mission, expectedVersion);
        int grantVersion = integer(
                request.get("grantVersion"),
                mission.activeGrantVersion() == null ? repository.nextGrantVersion(missionId) - 1 : mission.activeGrantVersion()
        );
        GrantRecord grant = repository.findGrant(missionId, grantVersion)
                .orElseThrow(() -> notFound("授权版本不存在。"));
        Map<String, Object> candidates = aiClient.shadowScenarios(Map.of(
                "missionId", String.valueOf(missionId),
                "templateId", mission.templateId(),
                "goal", mission.goal(),
                "projectId", mission.projectId() == null ? 0 : mission.projectId(),
                "constraints", mission.constraints(),
                "grantRules", grant.rules()
        ));
        List<Map<String, Object>> actions = mapList(candidates.get("actions"));
        List<Map<String, Object>> decisions = new ArrayList<>();
        int allowed = 0;
        int confirmation = 0;
        int denied = 0;
        for (Map<String, Object> candidate : actions) {
            ActionIntent action = policyEngine.normalize(
                    text(candidate.get("toolRef")),
                    text(candidate.get("riskLevel")),
                    bool(candidate.get("writeIntent")),
                    map(candidate.get("input"))
            );
            PolicyDecision decision = policyEngine.evaluate(userId, grant, action);
            if ("ALLOW".equals(decision.decision())) allowed++;
            else if ("REQUIRE_CONFIRMATION".equals(decision.decision())) confirmation++;
            else denied++;
            Map<String, Object> item = new LinkedHashMap<>(candidate);
            item.put("decision", decision.decision());
            item.put("reasonCode", decision.reasonCode());
            item.put("matchedRuleId", decision.matchedRuleId());
            item.put("riskLevel", action.riskLevel());
            item.put("reversibility", action.reversible() ? "AVAILABLE" : "NOT_SUPPORTED");
            decisions.add(item);
        }
        if ("DRAFT".equals(mission.status())) {
            if (!repository.transitionMission(missionId, userId, expectedVersion, "DRAFT", "SHADOW")) {
                throw conflict("持续任务已变化，请刷新后重试。");
            }
        } else if (!"SHADOW".equals(mission.status())) {
            throw conflict("只有草稿或预演中的持续任务可以执行 Shadow Mode。");
        }
        publish(missionId, "mission.updated", Map.of(
                "status", "SHADOW",
                "summary", "Shadow Mode 策略预演已完成。"
        ));
        return Map.of(
                "mission", detail(userId, missionId),
                "summary", Map.of("allow", allowed, "requireConfirmation", confirmation, "deny", denied),
                "decisions", decisions,
                "notice", "Shadow Mode 是策略预演；正式运行仍会在每个动作前重新检查当前授权。"
        );
    }

    @Transactional
    public Map<String, Object> submitForApproval(
            long userId,
            long missionId,
            long expectedVersion,
            int grantVersion
    ) {
        MissionRecord mission = requireMission(userId, missionId);
        requireVersion(mission, expectedVersion);
        if (!"SHADOW".equals(mission.status())) throw conflict("请先完成 Shadow Mode 预演。");
        repository.findGrant(missionId, grantVersion).orElseThrow(() -> notFound("授权版本不存在。"));
        repository.markGrantPending(missionId, grantVersion);
        if (!repository.transitionMission(
                missionId, userId, expectedVersion, "SHADOW", "AWAITING_APPROVAL")) {
            throw conflict("持续任务已变化，请刷新后重试。");
        }
        publish(missionId, "grant.updated", Map.of(
                "status", "PENDING_APPROVAL",
                "grantVersion", grantVersion,
                "summary", "授权等待用户批准。"
        ));
        publish(missionId, "mission.updated", Map.of(
                "status", "AWAITING_APPROVAL",
                "summary", "持续任务等待授权批准。"
        ));
        return detail(userId, missionId);
    }

    @Transactional
    public Map<String, Object> approveGrant(
            long userId,
            long missionId,
            int grantVersion,
            long expectedVersion
    ) {
        requireControlPlane(userId);
        MissionRecord mission = requireMission(userId, missionId);
        requireVersion(mission, expectedVersion);
        if (List.of("ACTIVE", "PAUSED").contains(mission.status())) {
            GrantRecord grant = repository.findGrant(missionId, grantVersion)
                    .orElseThrow(() -> notFound("授权版本不存在。"));
            if (grant.expiresAt().isAfter(mission.expiresAt())) {
                throw badRequest("授权有效期不能超过持续任务有效期。");
            }
            repository.approveGrant(missionId, grantVersion, userId);
            if (!repository.replaceActiveGrant(
                    missionId, userId, expectedVersion, grantVersion)) {
                throw conflict("持续任务授权状态已变化，请刷新后重试。");
            }
            publish(missionId, "grant.updated", Map.of(
                    "status", "ACTIVE",
                    "grantVersion", grantVersion,
                    "summary", "新授权版本已立即生效。"
            ));
            return detail(userId, missionId);
        }
        if (!"AWAITING_APPROVAL".equals(mission.status())) {
            throw conflict("持续任务当前不等待授权批准。");
        }
        GrantRecord grant = repository.findGrant(missionId, grantVersion)
                .orElseThrow(() -> notFound("授权版本不存在。"));
        if (grant.expiresAt().isAfter(mission.expiresAt())) {
            throw badRequest("授权有效期不能超过持续任务有效期。");
        }
        repository.approveGrant(missionId, grantVersion, userId);
        LocalDateTime nextRunAt = repository.listTriggers(missionId).stream()
                .filter(TriggerRecord::enabled)
                .map(TriggerRecord::nextFireAt)
                .filter(value -> value != null)
                .min(LocalDateTime::compareTo)
                .orElse(null);
        if (!repository.activateMission(
                missionId, userId, expectedVersion, grantVersion, nextRunAt)) {
            throw conflict("持续任务授权状态已变化，请刷新后重试。");
        }
        publish(missionId, "grant.updated", Map.of(
                "status", "ACTIVE",
                "grantVersion", grantVersion,
                "summary", "委托授权已批准。"
        ));
        publish(missionId, "mission.updated", Map.of(
                "status", "ACTIVE",
                "summary", "持续任务已启用。"
        ));
        return detail(userId, missionId);
    }

    @Transactional
    public Map<String, Object> createGrantVersion(
            long userId,
            long missionId,
            long expectedVersion,
            Map<String, Object> request
    ) {
        MissionRecord mission = requireMission(userId, missionId);
        requireVersion(mission, expectedVersion);
        List<Object> rules = list(request.get("rules"));
        policyEngine.validateRules(rules);
        Map<String, Object> budgetPolicy = request.containsKey("budgetPolicy")
                ? map(request.get("budgetPolicy"))
                : mission.periodBudget();
        LocalDateTime validFrom = now();
        LocalDateTime expiresAt = parseExpiry(request.get("expiresAt"), mission.expiresAt());
        if (expiresAt.isAfter(mission.expiresAt())) throw badRequest("授权有效期不能超过持续任务有效期。");
        int version = repository.nextGrantVersion(missionId);
        repository.createGrant(
                missionId,
                version,
                textOr(request.get("summary"), "用户调整了持续委托边界。"),
                rules,
                budgetPolicy,
                validFrom,
                expiresAt,
                grantHash(rules, budgetPolicy, validFrom, expiresAt)
        );
        GrantRecord previous = mission.activeGrantVersion() == null
                ? null
                : repository.findGrant(missionId, mission.activeGrantVersion()).orElse(null);
        boolean widening = previous == null || isWidening(previous, rules, budgetPolicy, expiresAt);
        if (!widening && ("ACTIVE".equals(mission.status()) || "PAUSED".equals(mission.status()))) {
            repository.approveGrant(missionId, version, userId);
            if (!repository.replaceActiveGrant(missionId, userId, expectedVersion, version)) {
                throw conflict("持续任务已变化，请刷新后重试。");
            }
        }
        Map<String, Object> result = detail(userId, missionId);
        publish(missionId, "grant.updated", Map.of(
                "status", !widening && List.of("ACTIVE", "PAUSED").contains(mission.status())
                        ? "ACTIVE" : "DRAFT",
                "grantVersion", version,
                "summary", widening ? "授权扩大，等待重新批准。" : "授权收窄已生效。"
        ));
        result.put("grantChange", Map.of(
                "version", version,
                "requiresApproval", widening,
                "effectiveImmediately", !widening
        ));
        return result;
    }

    @Transactional
    public Map<String, Object> replaceGrantVersion(
            long userId,
            long missionId,
            int baseVersion,
            long expectedVersion,
            Map<String, Object> request
    ) {
        requireMission(userId, missionId);
        repository.findGrant(missionId, baseVersion)
                .orElseThrow(() -> notFound("要编辑的授权版本不存在。"));
        int latestVersion = repository.nextGrantVersion(missionId) - 1;
        if (baseVersion != latestVersion) {
            throw conflict("只能基于最新授权版本创建不可变的新版本。");
        }
        return createGrantVersion(userId, missionId, expectedVersion, request);
    }

    public Map<String, Object> grantDiff(long userId, long missionId, int version) {
        requireMission(userId, missionId);
        GrantRecord current = repository.findGrant(missionId, version)
                .orElseThrow(() -> notFound("授权版本不存在。"));
        GrantRecord previous = repository.findGrant(missionId, Math.max(1, version - 1)).orElse(null);
        Set<String> currentRules = canonicalRules(current.rules());
        Set<String> previousRules = previous == null ? Set.of() : canonicalRules(previous.rules());
        Set<String> added = new LinkedHashSet<>(currentRules);
        added.removeAll(previousRules);
        Set<String> removed = new LinkedHashSet<>(previousRules);
        removed.removeAll(currentRules);
        return Map.of(
                "fromVersion", previous == null ? 0 : previous.version(),
                "toVersion", current.version(),
                "addedRules", added,
                "removedRules", removed,
                "budgetBefore", previous == null ? Map.of() : previous.budgetPolicy(),
                "budgetAfter", current.budgetPolicy()
        );
    }

    @Transactional
    public Map<String, Object> revokeGrant(
            long userId,
            long missionId,
            int grantVersion,
            long expectedVersion
    ) {
        MissionRecord mission = requireMission(userId, missionId);
        requireVersion(mission, expectedVersion);
        GrantRecord grant = repository.findGrant(missionId, grantVersion)
                .orElseThrow(() -> notFound("授权版本不存在。"));
        if (!repository.revokeGrant(missionId, grantVersion)) {
            throw conflict("授权版本已失效或不可撤销。");
        }
        if (mission.activeGrantVersion() != null
                && mission.activeGrantVersion() == grantVersion) {
            if (!repository.clearActiveGrantAndPause(
                    missionId, userId, expectedVersion, grantVersion)) {
                throw conflict("当前有效授权已变化，请刷新后重试。");
            }
        } else if ("AWAITING_APPROVAL".equals(mission.status())
                && List.of("DRAFT", "PENDING_APPROVAL").contains(grant.status())) {
            if (!repository.revertApprovalToShadow(missionId, userId, expectedVersion)) {
                throw conflict("持续任务审批状态已变化，请刷新后重试。");
            }
        } else if (!repository.touchMission(missionId, userId, expectedVersion)) {
            throw conflict("持续任务已变化，请刷新后重试。");
        }
        publish(missionId, "grant.revoked", Map.of(
                "status", "REVOKED",
                "grantVersion", grantVersion,
                "summary", "委托授权已撤销。"
        ));
        return detail(userId, missionId);
    }

    @Transactional
    public Map<String, Object> pause(long userId, long missionId, long expectedVersion) {
        MissionRecord mission = requireMission(userId, missionId);
        requireVersion(mission, expectedVersion);
        if (!repository.transitionMission(
                missionId, userId, expectedVersion, "ACTIVE", "PAUSED")) {
            throw conflict("只有运行中的持续任务可以暂停。");
        }
        publish(missionId, "mission.updated", Map.of(
                "status", "PAUSED",
                "summary", "持续任务已暂停。"
        ));
        return detail(userId, missionId);
    }

    @Transactional
    public Map<String, Object> resume(long userId, long missionId, long expectedVersion) {
        MissionRecord mission = requireMission(userId, missionId);
        requireVersion(mission, expectedVersion);
        if (mission.expiresAt().isBefore(now()) || mission.activeGrantVersion() == null) {
            throw conflict("持续任务已到期或没有有效授权，必须创建并批准新授权版本。");
        }
        GrantRecord grant = repository.findGrant(missionId, mission.activeGrantVersion())
                .orElseThrow(() -> conflict("有效授权不存在。"));
        if (!"ACTIVE".equals(grant.status()) || grant.expiresAt().isBefore(now())) {
            throw conflict("当前授权不可恢复。");
        }
        LocalDateTime nextRunAt = repository.listTriggers(missionId).stream()
                .filter(TriggerRecord::enabled)
                .map(TriggerRecord::nextFireAt)
                .filter(value -> value != null)
                .min(LocalDateTime::compareTo)
                .orElse(null);
        if (!repository.activateMission(
                missionId, userId, expectedVersion, mission.activeGrantVersion(), nextRunAt)) {
            throw conflict("只有暂停中的持续任务可以恢复。");
        }
        publish(missionId, "mission.updated", Map.of(
                "status", "ACTIVE",
                "summary", "持续任务已恢复。"
        ));
        return detail(userId, missionId);
    }

    @Transactional
    public Map<String, Object> revoke(long userId, long missionId, long expectedVersion) {
        MissionRecord mission = requireMission(userId, missionId);
        requireVersion(mission, expectedVersion);
        if (!repository.revokeMission(missionId, userId, expectedVersion)) {
            throw conflict("持续任务已变化或已撤销。");
        }
        repository.revokeGrants(missionId);
        publish(missionId, "mission.updated", Map.of(
                "status", "REVOKED",
                "summary", "持续任务及其有效授权已撤销。"
        ));
        if (mission.activeGrantVersion() != null) {
            publish(missionId, "grant.revoked", Map.of(
                    "status", "REVOKED",
                    "grantVersion", mission.activeGrantVersion(),
                    "summary", "随持续任务撤销的授权版本。"
            ));
        }
        return detail(userId, missionId);
    }

    public Map<String, Object> runNow(
            long userId,
            long missionId,
            String idempotencyKey
    ) {
        MissionRecord mission = requireMission(userId, missionId);
        if (!featureFlags.allowsExecution(userId, mission.templateId())) {
            throw forbidden("真实持续执行尚未对当前用户开放；可继续使用 Shadow Mode。");
        }
        if (!"ACTIVE".equals(mission.status())) throw conflict("只有运行中的持续任务可以立即执行。");
        validateIdempotencyKey(idempotencyKey);
        String key = sha256("manual:" + userId + ":" + missionId + ":" + idempotencyKey);
        OccurrenceRecord existing = repository.findOccurrenceByKey(key).orElse(null);
        if (existing != null) {
            Map<String, Object> replay = occurrenceRunMap(existing);
            if (existing.agentTaskId() != null) {
                replay.put("agentTask", agentTaskService.detail(userId, existing.agentTaskId()));
            }
            return replay;
        }
        TriggerRecord trigger = repository.listTriggers(missionId).stream().findFirst()
                .orElseThrow(() -> conflict("持续任务没有可用触发器。"));
        OccurrenceRecord occurrence = repository.createOccurrence(
                missionId,
                trigger.id(),
                now(),
                Map.of("source", "MANUAL", "requestedBy", userId),
                key
        ).orElseThrow(() -> conflict("相同触发正在处理中，请稍后刷新。"));
        triggered(mission.id(), trigger.type(), occurrence.id(), "PENDING", "手动触发已创建。");
        return executeOccurrence(occurrence.id(), "manual-" + UUID.randomUUID());
    }

    public Map<String, Object> listRuns(long userId, long missionId, int limit) {
        requireMission(userId, missionId);
        return Map.of("items", repository.listRuns(missionId, limit));
    }

    public Map<String, Object> listDecisions(long userId, long missionId, int limit) {
        requireMission(userId, missionId);
        return Map.of("items", repository.listAuthorizationDecisions(missionId, limit));
    }

    public Map<String, Object> listOccurrences(long userId, long missionId, int limit) {
        requireMission(userId, missionId);
        return Map.of("items", repository.listOccurrences(missionId, limit)
                .stream().map(this::occurrenceMap).toList());
    }

    @Transactional
    public Map<String, Object> createTrigger(
            long userId,
            long missionId,
            long expectedVersion,
            Map<String, Object> request
    ) {
        MissionRecord mission = requireMission(userId, missionId);
        requireVersion(mission, expectedVersion);
        long triggerId = createTriggerInternal(missionId, request, true);
        if (!repository.touchMission(missionId, userId, expectedVersion)) {
            throw conflict("持续任务已变化，请刷新后重试。");
        }
        refreshMissionNextRun(missionId);
        return repository.findTrigger(triggerId, missionId).map(this::triggerMap)
                .orElseThrow(() -> conflict("触发器创建失败。"));
    }

    @Transactional
    public Map<String, Object> updateTrigger(
            long userId,
            long missionId,
            long triggerId,
            long expectedVersion,
            Map<String, Object> request
    ) {
        MissionRecord mission = requireMission(userId, missionId);
        requireVersion(mission, expectedVersion);
        TriggerRecord current = repository.findTrigger(triggerId, missionId)
                .orElseThrow(() -> notFound("触发器不存在。"));
        long triggerVersion = request.get("triggerVersion") == null
                ? current.version()
                : longValue(request.get("triggerVersion"));
        String type = textOr(request.get("type"), current.type()).toUpperCase(Locale.ROOT);
        Map<String, Object> config = request.containsKey("config")
                ? map(request.get("config"))
                : current.config();
        String timezone = textOr(request.get("timezone"), current.timezone());
        String misfirePolicy = textOr(
                request.get("misfirePolicy"), current.misfirePolicy()).toUpperCase(Locale.ROOT);
        boolean enabled = request.containsKey("enabled")
                ? bool(request.get("enabled"))
                : current.enabled();
        schedules.validate(type, config, timezone, misfirePolicy);
        LocalDateTime nextFire = enabled
                ? schedules.nextFire(type, config, timezone, now())
                : null;
        if (!repository.updateTrigger(
                triggerId, missionId, triggerVersion, type, config, timezone,
                misfirePolicy, enabled, nextFire)) {
            throw conflict("触发器已变化，请刷新后重试。");
        }
        if (!repository.touchMission(missionId, userId, expectedVersion)) {
            throw conflict("持续任务已变化，请刷新后重试。");
        }
        refreshMissionNextRun(missionId);
        return repository.findTrigger(triggerId, missionId).map(this::triggerMap)
                .orElseThrow(() -> conflict("触发器更新失败。"));
    }

    @Transactional
    public Map<String, Object> deleteTrigger(
            long userId,
            long missionId,
            long triggerId,
            long expectedVersion,
            long triggerVersion
    ) {
        MissionRecord mission = requireMission(userId, missionId);
        requireVersion(mission, expectedVersion);
        TriggerRecord current = repository.findTrigger(triggerId, missionId)
                .orElseThrow(() -> notFound("触发器不存在。"));
        long effectiveVersion = triggerVersion < 0 ? current.version() : triggerVersion;
        if (!repository.disableTrigger(triggerId, missionId, effectiveVersion)) {
            throw conflict("触发器已变化或已经停用。");
        }
        if (!repository.touchMission(missionId, userId, expectedVersion)) {
            throw conflict("持续任务已变化，请刷新后重试。");
        }
        refreshMissionNextRun(missionId);
        return repository.findTrigger(triggerId, missionId).map(this::triggerMap)
                .orElseThrow(() -> conflict("触发器停用失败。"));
    }

    public Map<String, Object> digest(long userId, int limit) {
        requireControlPlane(userId);
        List<ExceptionRecord> exceptions = repository.listExceptions(userId, null, 200);
        List<Map<String, Object>> items = new ArrayList<>();
        int completedRuns = 0;
        int attentionCount = 0;
        for (MissionRecord mission : repository.listMissions(userId, null, limit)) {
            List<Map<String, Object>> runs = repository.listRuns(mission.id(), 50);
            List<Map<String, Object>> missionExceptions = exceptions.stream()
                    .filter(item -> item.missionId() == mission.id())
                    .map(this::exceptionMap)
                    .toList();
            Map<String, Object> digest = aiClient.digest(Map.of(
                    "missionId", String.valueOf(mission.id()),
                    "runs", runs,
                    "exceptions", missionExceptions
            ));
            completedRuns += integer(digest.get("completedRuns"), 0);
            attentionCount += integer(digest.get("attentionCount"), 0);
            Map<String, Object> item = new LinkedHashMap<>();
            Map<String, Object> periodUsage = periodUsage(mission);
            item.put("missionId", String.valueOf(mission.id()));
            item.put("title", mission.title());
            item.put("status", mission.status());
            item.put("nextRunAt", iso(mission.nextRunAt()));
            item.put("expiresAt", iso(mission.expiresAt()));
            item.put("periodUsage", periodUsage);
            BudgetUsageRecord usage = usageRecord(periodUsage);
            item.put("nearBudget", nearBudget(mission.periodBudget(), usage));
            item.putAll(digest);
            items.add(item);
        }
        return Map.of(
                "items", items,
                "completedRuns", completedRuns,
                "attentionCount", attentionCount
        );
    }

    public Map<String, Object> listExceptions(long userId, String status, int limit) {
        requireControlPlane(userId);
        return Map.of("items", repository.listExceptions(userId, normalizeOptionalStatus(status), limit)
                .stream().map(this::exceptionMap).toList());
    }

    public Map<String, Object> getException(long userId, long exceptionId) {
        requireControlPlane(userId);
        return exceptionMap(repository.findException(exceptionId, userId)
                .orElseThrow(() -> notFound("异常事项不存在。")));
    }

    public Map<String, Object> acknowledgeException(long userId, long exceptionId) {
        ExceptionRecord value = repository.findException(exceptionId, userId)
                .orElseThrow(() -> notFound("异常事项不存在。"));
        if (!repository.updateExceptionStatus(exceptionId, userId, value.status(), "ACKNOWLEDGED")) {
            throw conflict("异常事项状态已变化。");
        }
        publish(value.missionId(), "exception.resolved", Map.of(
                "status", "ACKNOWLEDGED",
                "exceptionId", String.valueOf(exceptionId),
                "summary", "异常事项已确认。"
        ));
        return getException(userId, exceptionId);
    }

    public Map<String, Object> resolveException(long userId, long exceptionId, String action) {
        ExceptionRecord value = repository.findException(exceptionId, userId)
                .orElseThrow(() -> notFound("异常事项不存在。"));
        String normalized = text(action).toUpperCase(Locale.ROOT);
        if (!List.of("CONTINUE", "SKIP", "PROVIDE_INPUT", "ADJUST_GRANT", "END_RUN", "VIEW_EVIDENCE").contains(normalized)) {
            throw badRequest("异常解决动作不在白名单中。");
        }
        if (!repository.updateExceptionStatus(exceptionId, userId, value.status(), "RESOLVED")) {
            throw conflict("异常事项状态已变化。");
        }
        Map<String, Object> result = getException(userId, exceptionId);
        result.put("resolutionAction", normalized);
        publish(value.missionId(), "exception.resolved", Map.of(
                "status", "RESOLVED",
                "exceptionId", String.valueOf(exceptionId),
                "resolutionAction", normalized,
                "summary", "异常事项已解决。"
        ));
        return result;
    }

    public Map<String, Object> dismissException(long userId, long exceptionId) {
        ExceptionRecord value = repository.findException(exceptionId, userId)
                .orElseThrow(() -> notFound("异常事项不存在。"));
        if (!repository.updateExceptionStatus(exceptionId, userId, value.status(), "DISMISSED")) {
            throw conflict("异常事项状态已变化。");
        }
        publish(value.missionId(), "exception.resolved", Map.of(
                "status", "DISMISSED",
                "exceptionId", String.valueOf(exceptionId),
                "summary", "异常事项已忽略。"
        ));
        return getException(userId, exceptionId);
    }

    public Map<String, Object> compensation(long userId, long stepRunId) {
        requireControlPlane(userId);
        CompensationRecord value = repository.findCompensationByStep(stepRunId)
                .filter(item -> item.userId() == userId)
                .orElseThrow(() -> notFound("该动作没有可用补偿记录。"));
        return compensationMap(value);
    }

    public Map<String, Object> compensationById(long userId, long compensationId) {
        requireControlPlane(userId);
        return compensationMap(repository.findCompensation(compensationId, userId)
                .orElseThrow(() -> notFound("补偿记录不存在。")));
    }

    public Map<String, Object> compensate(long userId, long stepRunId, String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);
        CompensationRecord value = repository.findCompensationByStep(stepRunId)
                .filter(item -> item.userId() == userId)
                .orElseThrow(() -> notFound("该动作没有可用补偿记录。"));
        if (value.expiresAt().isBefore(now())) {
            repository.finishCompensation(value.id(), "EXPIRED");
            compensationUpdated(value, "EXPIRED", "补偿记录已过期。");
            throw conflict("该动作已超过撤销期限。");
        }
        if ("SUCCEEDED".equals(value.status())) return compensationMap(value);
        if (!repository.requestCompensation(value.id(), userId) && !"REQUESTED".equals(value.status())) {
            throw conflict("补偿状态已变化，请刷新后重试。");
        }
        compensationUpdated(value, "REQUESTED", "补偿操作已请求。");
        Map<String, Object> result = aiClient.compensate(Map.of(
                "userId", String.valueOf(userId),
                "snapshotRef", value.payloadRef(),
                "idempotencyKey", idempotencyKey
        ));
        String status = textOr(result.get("status"), "FAILED").toUpperCase(Locale.ROOT);
        if (!List.of("SUCCEEDED", "CONFLICTED", "FAILED", "EXPIRED").contains(status)) status = "FAILED";
        repository.finishCompensation(value.id(), status);
        compensationUpdated(value, status, "补偿操作状态已更新。");
        if ("CONFLICTED".equals(status)) {
            long exceptionId = repository.openException(
                    userId,
                    value.missionId(),
                    value.agentTaskId(),
                    "CONFLICT",
                    "HIGH",
                    sha256("compensation:" + value.id()),
                    "自动变更无法安全撤销",
                    "外部状态已发生变化，Lyle 未覆盖当前内容。",
                    List.of("compensation://" + value.id()),
                    List.of("VIEW_EVIDENCE", "DISMISS")
            );
            exceptionOpened(
                    value.missionId(), exceptionId, "CONFLICT", "HIGH",
                    "自动变更无法安全撤销");
        }
        Map<String, Object> response = compensationMap(
                repository.findCompensation(value.id(), userId).orElse(value));
        response.put("result", result);
        return response;
    }

    Map<String, Object> executeOccurrence(long occurrenceId, String workerId) {
        OccurrenceRecord occurrence = repository.findOccurrence(occurrenceId)
                .orElseThrow(() -> notFound("触发记录不存在。"));
        if (occurrence.agentTaskId() != null) return occurrenceRunMap(occurrence);
        if (!repository.claimOccurrence(occurrenceId, workerId, 60)) {
            OccurrenceRecord refreshed = repository.findOccurrence(occurrenceId).orElse(occurrence);
            return occurrenceRunMap(refreshed);
        }
        MissionRecord mission = repository.findMission(occurrence.missionId()).orElse(null);
        if (mission == null || !"ACTIVE".equals(mission.status())
                || mission.activeGrantVersion() == null || !mission.expiresAt().isAfter(now())) {
            repository.markOccurrenceFailed(occurrenceId);
            occurrenceMetric("UNKNOWN", "FAILED");
            throw conflict("持续任务或授权当前不可执行。");
        }
        GrantRecord grant = repository.findGrant(mission.id(), mission.activeGrantVersion()).orElse(null);
        if (grant == null || !"ACTIVE".equals(grant.status()) || !grant.expiresAt().isAfter(now())) {
            repository.markOccurrenceFailed(occurrenceId);
            occurrenceMetric("UNKNOWN", "FAILED");
            throw conflict("持续任务没有有效授权。");
        }
        try {
            Map<String, Object> delegatedConstraints = new LinkedHashMap<>(mission.constraints());
            delegatedConstraints.put("delegatedAllowedTools", policyEngine.plannedToolRefs(grant));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("title", mission.title() + " · 自动运行");
            payload.put("goal", mission.goal());
            payload.put("completionCriteria", mission.completionCriteria());
            payload.put("constraints", delegatedConstraints);
            payload.put("templateId", mission.templateId());
            payload.put("autonomyLevel", hasR1(grant.rules()) ? 2 : 1);
            if (mission.projectId() != null) payload.put("projectId", mission.projectId());
            Map<String, Object> task = agentTaskService.createDelegatedRun(
                    mission.userId(),
                    mission.id(),
                    occurrence.id(),
                    grant.version(),
                    payload,
                    repository
            );
            long taskId = longValue(task.get("id"));
            repository.attachTaskToOccurrence(occurrence.id(), taskId);
            occurrenceMetric("EXECUTION", "TASK_CREATED");
            if (metrics != null) metrics.run(mission.templateId(), "CREATED");
            publish(mission.id(), "mission.run.created", Map.of(
                    "status", "TASK_CREATED",
                    "occurrenceId", String.valueOf(occurrence.id()),
                    "agentTaskId", String.valueOf(taskId),
                    "summary", "持续任务运行已创建。"
            ));
            return Map.of(
                    "occurrence", occurrenceMap(repository.findOccurrence(occurrence.id()).orElse(occurrence)),
                    "agentTask", task
            );
        } catch (RuntimeException exc) {
            repository.markOccurrenceFailed(occurrence.id());
            occurrenceMetric("EXECUTION", "FAILED");
            long exceptionId = repository.openException(
                    mission.userId(),
                    mission.id(),
                    null,
                    "FAILURE",
                    "HIGH",
                    sha256("occurrence:" + occurrence.id()),
                    "持续任务运行创建失败",
                    safeMessage(exc),
                    List.of("occurrence://" + occurrence.id()),
                    List.of("CONTINUE", "VIEW_EVIDENCE", "END_RUN")
            );
            exceptionOpened(
                    mission.id(), exceptionId, "FAILURE", "HIGH",
                    "持续任务运行创建失败");
            throw exc;
        }
    }

    long createScheduledOccurrence(TriggerRecord trigger, LocalDateTime scheduledFor) {
        String key = sha256(
                trigger.missionId() + ":" + trigger.id() + ":" + scheduledFor.atOffset(ZoneOffset.UTC));
        long occurrenceId = repository.createOccurrence(
                trigger.missionId(),
                trigger.id(),
                scheduledFor,
                Map.of("source", "SCHEDULE", "triggerType", trigger.type()),
                key
        ).map(OccurrenceRecord::id).orElseThrow(() -> conflict("触发幂等记录创建失败。"));
        triggered(
                trigger.missionId(), trigger.type(), occurrenceId,
                "PENDING", "计划触发已创建。");
        return occurrenceId;
    }

    long createSkippedScheduledOccurrence(
            TriggerRecord trigger,
            LocalDateTime scheduledFor,
            String reasonCode
    ) {
        String key = sha256(
                trigger.missionId() + ":" + trigger.id() + ":"
                        + scheduledFor.atOffset(ZoneOffset.UTC));
        OccurrenceRecord occurrence = repository.createOccurrence(
                trigger.missionId(),
                trigger.id(),
                scheduledFor,
                Map.of(
                        "source", "SCHEDULE",
                        "triggerType", trigger.type(),
                        "skipReason", reasonCode
                ),
                key
        ).orElseGet(() -> repository.findOccurrenceByKey(key)
                .orElseThrow(() -> conflict("跳过触发幂等记录创建失败。")));
        repository.markOccurrenceSkipped(occurrence.id());
        triggered(
                trigger.missionId(),
                trigger.type(),
                occurrence.id(),
                "SKIPPED",
                "授权已到期，计划触发已跳过且不会自动续期。"
        );
        return occurrence.id();
    }

    private long createTriggerInternal(long missionId, Map<String, Object> request, boolean enabled) {
        String type = textOr(request.get("type"), "FIXED_TIME").toUpperCase(Locale.ROOT);
        Map<String, Object> config = map(request.get("config"));
        String timezone = textOr(request.get("timezone"), "Asia/Shanghai");
        String misfirePolicy = textOr(request.get("misfirePolicy"), "RUN_ONCE").toUpperCase(Locale.ROOT);
        schedules.validate(type, config, timezone, misfirePolicy);
        LocalDateTime nextFire = schedules.nextFire(type, config, timezone, now());
        return repository.createTrigger(
                missionId, type, config, timezone, misfirePolicy, enabled, nextFire);
    }

    void refreshMissionNextRun(long missionId) {
        LocalDateTime next = repository.listTriggers(missionId).stream()
                .filter(TriggerRecord::enabled)
                .map(TriggerRecord::nextFireAt)
                .filter(value -> value != null)
                .min(LocalDateTime::compareTo)
                .orElse(null);
        repository.updateMissionNextRun(missionId, next);
    }

    private static boolean nearBudget(
            Map<String, Object> limits,
            BudgetUsageRecord usage
    ) {
        return ratio(limits, "maxToolCalls", usage.toolCalls()) >= 0.8
                || ratio(limits, "maxModelTokens", usage.modelTokens()) >= 0.8
                || ratio(limits, "maxCostMicros", usage.costMicros()) >= 0.8
                || ratio(limits, "maxWallTimeMillis", usage.elapsedMillis()) >= 0.8;
    }

    private static double ratio(Map<String, Object> limits, String key, long used) {
        long limit = longValue(limits.get(key));
        return limit <= 0 ? 0 : (double) used / (double) limit;
    }

    private Map<String, Object> periodUsage(MissionRecord mission) {
        PeriodBounds period = periodBounds(mission.periodType(), now());
        BudgetUsageRecord usage = repository.aggregateMissionUsage(
                mission.id(), period.start(), period.end());
        if (metrics != null) {
            metrics.periodBudgetRatio(Math.max(
                    Math.max(
                            ratio(mission.periodBudget(), "maxToolCalls", usage.toolCalls()),
                            ratio(mission.periodBudget(), "maxModelTokens", usage.modelTokens())),
                    Math.max(
                            ratio(mission.periodBudget(), "maxCostMicros", usage.costMicros()),
                            ratio(mission.periodBudget(), "maxWallTimeMillis", usage.elapsedMillis()))
            ));
        }
        return Map.of(
                "completedSteps", usage.completedSteps(),
                "toolCalls", usage.toolCalls(),
                "modelTokens", usage.modelTokens(),
                "costMicros", usage.costMicros(),
                "wallTimeMillis", usage.elapsedMillis(),
                "periodStart", iso(period.start()),
                "periodEnd", iso(period.end())
        );
    }

    private void triggered(
            long missionId,
            String triggerType,
            long occurrenceId,
            String status,
            String summary
    ) {
        occurrenceMetric(triggerType, status);
        publish(missionId, "mission.triggered", Map.of(
                "status", status,
                "occurrenceId", String.valueOf(occurrenceId),
                "triggerType", triggerType,
                "summary", summary
        ));
    }

    private void occurrenceMetric(String type, String status) {
        if (metrics != null) metrics.occurrence(type, status);
    }

    private void exceptionOpened(
            long missionId,
            long exceptionId,
            String type,
            String severity,
            String summary
    ) {
        if (metrics != null) metrics.exception(type, severity);
        publish(missionId, "exception.opened", Map.of(
                "status", "OPEN",
                "exceptionId", String.valueOf(exceptionId),
                "type", type,
                "severity", severity,
                "summary", summary
        ));
    }

    private void compensationUpdated(
            CompensationRecord value,
            String status,
            String summary
    ) {
        if (metrics != null) metrics.compensation(status, value.toolRef());
        publish(value.missionId(), "compensation.updated", Map.of(
                "status", status,
                "compensationId", String.valueOf(value.id()),
                "stepRunId", String.valueOf(value.stepRunId()),
                "toolRef", value.toolRef(),
                "summary", summary
        ));
    }

    private void publish(long missionId, String eventType, Map<String, Object> payload) {
        if (missionEvents != null) missionEvents.publish(missionId, eventType, payload);
    }

    private static BudgetUsageRecord usageRecord(Map<String, Object> usage) {
        return new BudgetUsageRecord(
                longValue(usage.get("completedSteps")),
                longValue(usage.get("toolCalls")),
                longValue(usage.get("modelTokens")),
                longValue(usage.get("costMicros")),
                longValue(usage.get("wallTimeMillis"))
        );
    }

    private static PeriodBounds periodBounds(String periodType, LocalDateTime value) {
        LocalDate date = value.toLocalDate();
        String normalized = text(periodType).toUpperCase(Locale.ROOT);
        LocalDate start = switch (normalized) {
            case "DAILY" -> date;
            case "MONTHLY" -> date.withDayOfMonth(1);
            default -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        };
        LocalDate end = switch (normalized) {
            case "DAILY" -> start.plusDays(1);
            case "MONTHLY" -> start.plusMonths(1);
            default -> start.plusWeeks(1);
        };
        return new PeriodBounds(start.atStartOfDay(), end.atStartOfDay());
    }

    private record PeriodBounds(LocalDateTime start, LocalDateTime end) { }

    private Map<String, Object> draftRequest(long userId, Map<String, Object> request) {
        Map<String, Object> value = new LinkedHashMap<>(request == null ? Map.of() : request);
        value.put("userId", String.valueOf(userId));
        value.putIfAbsent("timezone", "Asia/Shanghai");
        value.put("maxValidityDays", MAX_VALID_DAYS);
        return value;
    }

    private Map<String, Object> missionMap(MissionRecord mission) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", String.valueOf(mission.id()));
        result.put("projectId", mission.projectId() == null ? null : String.valueOf(mission.projectId()));
        result.put("title", mission.title());
        result.put("goal", mission.goal());
        result.put("completionCriteria", mission.completionCriteria());
        result.put("constraints", mission.constraints());
        result.put("templateId", mission.templateId());
        result.put("status", mission.status());
        result.put("activeGrantVersion", mission.activeGrantVersion());
        result.put("notificationPolicy", mission.notificationPolicy());
        result.put("quietHours", mission.quietHours());
        result.put("singleRunBudget", mission.singleRunBudget());
        result.put("periodBudget", mission.periodBudget());
        result.put("periodType", mission.periodType());
        result.put("validFrom", iso(mission.validFrom()));
        result.put("expiresAt", iso(mission.expiresAt()));
        result.put("nextRunAt", iso(mission.nextRunAt()));
        result.put("version", mission.version());
        result.put("createdAt", iso(mission.createdAt()));
        result.put("updatedAt", iso(mission.updatedAt()));
        return result;
    }

    private Map<String, Object> missionListItem(MissionRecord mission) {
        Map<String, Object> result = missionMap(mission);
        result.put("triggerCount", repository.listTriggers(mission.id()).size());
        List<Map<String, Object>> runs = repository.listRuns(mission.id(), 1);
        result.put("latestRun", runs.isEmpty() ? null : runs.get(0));
        return result;
    }

    private Map<String, Object> grantMap(GrantRecord grant) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", String.valueOf(grant.id()));
        result.put("missionId", String.valueOf(grant.missionId()));
        result.put("version", grant.version());
        result.put("status", grant.status());
        result.put("summary", grant.summary());
        result.put("rules", grant.rules());
        result.put("budgetPolicy", grant.budgetPolicy());
        result.put("validFrom", iso(grant.validFrom()));
        result.put("expiresAt", iso(grant.expiresAt()));
        result.put("approvedBy", grant.approvedBy());
        result.put("approvedAt", iso(grant.approvedAt()));
        result.put("contentHash", grant.contentHash());
        result.put("createdAt", iso(grant.createdAt()));
        return result;
    }

    private Map<String, Object> triggerMap(TriggerRecord trigger) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", String.valueOf(trigger.id()));
        result.put("missionId", String.valueOf(trigger.missionId()));
        result.put("type", trigger.type());
        result.put("config", trigger.config());
        result.put("timezone", trigger.timezone());
        result.put("misfirePolicy", trigger.misfirePolicy());
        result.put("enabled", trigger.enabled());
        result.put("nextFireAt", iso(trigger.nextFireAt()));
        result.put("version", trigger.version());
        return result;
    }

    private Map<String, Object> occurrenceMap(OccurrenceRecord occurrence) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", String.valueOf(occurrence.id()));
        result.put("missionId", String.valueOf(occurrence.missionId()));
        result.put("triggerId", String.valueOf(occurrence.triggerId()));
        result.put("scheduledFor", iso(occurrence.scheduledFor()));
        result.put("payload", occurrence.payload());
        result.put("status", occurrence.status());
        result.put("agentTaskId", occurrence.agentTaskId() == null ? null : String.valueOf(occurrence.agentTaskId()));
        result.put("createdAt", iso(occurrence.createdAt()));
        result.put("completedAt", iso(occurrence.completedAt()));
        return result;
    }

    private Map<String, Object> occurrenceRunMap(OccurrenceRecord occurrence) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("occurrence", occurrenceMap(occurrence));
        if (occurrence.agentTaskId() != null) {
            result.put("agentTaskId", String.valueOf(occurrence.agentTaskId()));
        }
        return result;
    }

    private Map<String, Object> exceptionMap(ExceptionRecord value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", String.valueOf(value.id()));
        result.put("missionId", String.valueOf(value.missionId()));
        result.put("agentTaskId", value.agentTaskId() == null ? null : String.valueOf(value.agentTaskId()));
        result.put("type", value.type());
        result.put("severity", value.severity());
        result.put("title", value.title());
        result.put("summary", value.summary());
        result.put("evidenceRefs", value.evidenceRefs());
        result.put("availableActions", value.availableActions());
        result.put("status", value.status());
        result.put("createdAt", iso(value.createdAt()));
        result.put("resolvedAt", iso(value.resolvedAt()));
        return result;
    }

    private Map<String, Object> compensationMap(CompensationRecord value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", String.valueOf(value.id()));
        result.put("missionId", String.valueOf(value.missionId()));
        result.put("agentTaskId", String.valueOf(value.agentTaskId()));
        result.put("stepRunId", String.valueOf(value.stepRunId()));
        result.put("toolRef", value.toolRef());
        result.put("operation", value.operation());
        result.put("status", value.status());
        result.put("expiresAt", iso(value.expiresAt()));
        result.put("requestedAt", iso(value.requestedAt()));
        result.put("completedAt", iso(value.completedAt()));
        return result;
    }

    private static List<String> availableActions(MissionRecord mission) {
        return switch (mission.status()) {
            case "DRAFT" -> List.of("SHADOW", "REVOKE");
            case "SHADOW" -> List.of("SHADOW", "SUBMIT_APPROVAL", "REVOKE");
            case "AWAITING_APPROVAL" -> List.of("APPROVE_GRANT", "REVOKE");
            case "ACTIVE" -> List.of("RUN_NOW", "PAUSE", "REVOKE", "EDIT_GRANT");
            case "PAUSED" -> List.of("RESUME", "REVOKE", "EDIT_GRANT");
            default -> List.of();
        };
    }

    private boolean isWidening(
            GrantRecord previous,
            List<Object> nextRules,
            Map<String, Object> nextBudget,
            LocalDateTime nextExpiry
    ) {
        Set<String> previousAllows = canonicalRules(previous.rules(), "allow");
        Set<String> currentAllows = canonicalRules(nextRules, "allow");
        if (!previousAllows.containsAll(currentAllows)) return true;
        Set<String> previousDenies = canonicalRules(previous.rules(), "deny");
        Set<String> currentDenies = canonicalRules(nextRules, "deny");
        if (!currentDenies.containsAll(previousDenies)) return true;
        if (nextExpiry.isAfter(previous.expiresAt())) return true;
        for (String key : List.of("maxToolCalls", "maxModelTokens", "maxCostMicros", "maxWallTimeMillis")) {
            if (longValue(nextBudget.get(key)) > longValue(previous.budgetPolicy().get(key))) return true;
        }
        return false;
    }

    private Set<String> canonicalRules(List<Object> rules) {
        return canonicalRules(rules, null);
    }

    private Set<String> canonicalRules(List<Object> rules, String effectFilter) {
        Set<String> result = new LinkedHashSet<>();
        for (Object rule : rules) {
            Map<String, Object> value = map(rule);
            String effect = textOr(value.get("effect"), "deny").toLowerCase(Locale.ROOT);
            if (effectFilter != null && !effectFilter.equals(effect)) continue;
            try {
                result.add(objectMapper.writeValueAsString(rule));
            } catch (JsonProcessingException exc) {
                result.add(String.valueOf(rule));
            }
        }
        return result;
    }

    private String grantHash(
            List<Object> rules,
            Map<String, Object> budget,
            LocalDateTime validFrom,
            LocalDateTime expiresAt
    ) {
        try {
            return sha256(objectMapper.writeValueAsString(Map.of(
                    "rules", rules,
                    "budget", budget,
                    "validFrom", validFrom.toString(),
                    "expiresAt", expiresAt.toString()
            )));
        } catch (JsonProcessingException exc) {
            throw badRequest("授权内容无法序列化。");
        }
    }

    private MissionRecord requireMission(long userId, long missionId) {
        requireControlPlane(userId);
        return repository.findMission(missionId, userId)
                .orElseThrow(() -> notFound("持续任务不存在。"));
    }

    private void requireControlPlane(long userId) {
        if (!featureFlags.allowsControlPlane(userId)) {
            throw forbidden("可信委托与持续执行尚未对当前用户开放。");
        }
    }

    private static void requireVersion(MissionRecord mission, long expectedVersion) {
        if (mission.version() != expectedVersion) throw conflict("持续任务已变化，请刷新后重试。");
    }

    private static boolean hasR1(List<Object> rules) {
        return mapList(rules).stream().anyMatch(rule -> "R1".equalsIgnoreCase(text(rule.get("maxRisk"))));
    }

    private static LocalDateTime parseExpiry(Object value, LocalDateTime fallback) {
        if (value == null || text(value).isBlank()) return fallback;
        try {
            return OffsetDateTime.parse(String.valueOf(value))
                    .withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (RuntimeException exc) {
            throw badRequest("expiresAt 必须是带时区的 ISO-8601 时间。");
        }
    }

    private static void validateIdempotencyKey(String value) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw badRequest("Idempotency-Key 不能为空且不能超过 128 字符。");
        }
    }

    private Map<String, Object> idempotent(
            long userId,
            String actionKey,
            String idempotencyKey,
            Supplier<Map<String, Object>> operation
    ) {
        validateIdempotencyKey(idempotencyKey);
        MutationIdempotencyRecord existing = repository.findMutation(
                userId, actionKey, idempotencyKey).orElse(null);
        if (existing != null) {
            if (existing.completed()) return existing.response();
            throw conflict("相同幂等请求正在提交，请稍后重试。");
        }
        if (!repository.reserveMutation(userId, actionKey, idempotencyKey)) {
            MutationIdempotencyRecord winner = repository.findMutation(
                    userId, actionKey, idempotencyKey).orElse(null);
            if (winner != null && winner.completed()) return winner.response();
            throw conflict("相同幂等请求正在提交，请稍后重试。");
        }
        Map<String, Object> response = operation.get();
        repository.completeMutation(userId, actionKey, idempotencyKey, response);
        return response;
    }

    private static String normalizeOptionalStatus(String status) {
        String value = text(status).toUpperCase(Locale.ROOT);
        return value.isBlank() ? null : value;
    }

    private static String safeMessage(RuntimeException exc) {
        String value = exc.getMessage();
        if (value == null || value.isBlank()) return "持续任务运行创建失败。";
        return value.length() > 2_000 ? value.substring(0, 2_000) : value;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exc) {
            throw new IllegalStateException(exc);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> source
                ? new LinkedHashMap<>((Map<String, Object>) source)
                : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return value instanceof List<?> source
                ? new ArrayList<>((List<Object>) source)
                : new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> source)) return new ArrayList<>();
        return source.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) new LinkedHashMap<>((Map<String, Object>) item))
                .toList();
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null ? 0 : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static Long nullableLong(Object value) {
        long parsed = longValue(value);
        return parsed > 0 ? parsed : null;
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean result && result;
    }

    private static String requiredText(Map<String, Object> source, String key, int maxLength) {
        String value = text(source.get(key));
        if (value.isBlank()) throw badRequest(key + " 不能为空。");
        if (value.length() > maxLength) throw badRequest(key + " 过长。");
        return value;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String textOr(Object value, String fallback) {
        String result = text(value);
        return result.isBlank() ? fallback : result;
    }

    private static String iso(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC).toString();
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static ResponseStatusException forbidden(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
