package com.example.aiapp.agenttask.application;

import com.example.aiapp.agenttask.domain.AgentBudget;
import com.example.aiapp.agenttask.config.AgentTaskFeatureFlags;
import com.example.aiapp.agenttask.domain.AgentStepStatus;
import com.example.aiapp.agenttask.domain.AgentTaskStatus;
import com.example.aiapp.agenttask.domain.policy.AgentPolicyEngine;
import com.example.aiapp.agenttask.domain.policy.AgentConstraintPolicy;
import com.example.aiapp.agenttask.domain.state.AgentTaskStateMachine;
import com.example.aiapp.agenttask.infrastructure.events.AgentEventPublisher;
import com.example.aiapp.agenttask.infrastructure.python.AgentDecisionClient;
import com.example.aiapp.agenttask.infrastructure.repository.AgentTaskRepository;
import com.example.aiapp.agenttask.infrastructure.repository.AgentTaskRepository.ChangePreviewRecord;
import com.example.aiapp.agenttask.infrastructure.repository.AgentTaskRepository.PlanRecord;
import com.example.aiapp.agenttask.infrastructure.repository.AgentTaskRepository.PlanStepRecord;
import com.example.aiapp.agenttask.infrastructure.repository.AgentTaskRepository.RunRecord;
import com.example.aiapp.agenttask.infrastructure.repository.AgentTaskRepository.TaskRecord;
import com.example.aiapp.agenttask.infrastructure.worker.AgentRunWorker;
import com.example.aiapp.mission.infrastructure.MissionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 智能体任务编排应用服务。
 */
@Service
public class AgentTaskService {

    private final AgentTaskRepository repository;
    private final AgentTaskStateMachine stateMachine;
    private final AgentDecisionClient decisionClient;
    private final AgentEventPublisher events;
    private final AgentRunWorker worker;
    private final AgentTaskFeatureFlags featureFlags;

    public AgentTaskService(
            AgentTaskRepository repository,
            AgentTaskStateMachine stateMachine,
            AgentDecisionClient decisionClient,
            AgentEventPublisher events,
            AgentRunWorker worker,
            AgentTaskFeatureFlags featureFlags
    ) {
        this.repository = repository;
        this.stateMachine = stateMachine;
        this.decisionClient = decisionClient;
        this.events = events;
        this.worker = worker;
        this.featureFlags = featureFlags;
    }

    @Transactional
    public Map<String, Object> create(long userId, String idempotencyKey, Map<String, Object> request) {
        validateIdempotencyKey(idempotencyKey);
        Long existing = repository.findIdempotentTask(userId, "CREATE", idempotencyKey).orElse(null);
        if (existing != null) return detail(userId, existing);
        String goal = requiredText(request, "goal", 10_000);
        String title = text(request.get("title"));
        if (title.isBlank()) title = goal.length() > 80 ? goal.substring(0, 80) : goal;
        List<Object> criteria = list(request.get("completionCriteria"));
        if (criteria.isEmpty()) criteria = List.of("目标获得可验证的结果和证据");
        Map<String, Object> constraints = map(request.get("constraints"));
        String templateId = nullableText(request.get("templateId"));
        if (!featureFlags.allowsCreation(userId, templateId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Agent Task 尚未对白名单外用户或场景开放。");
        }
        int autonomyLevel = integer(request.get("autonomyLevel"), 1);
        if (autonomyLevel < 0 || autonomyLevel > 2) {
            throw badRequest("autonomyLevel 只支持 0～2。");
        }
        long taskId = repository.nextId();
        if (!repository.reserveIdempotency(userId, "CREATE", idempotencyKey, taskId)) {
            long winner = repository.findIdempotentTask(userId, "CREATE", idempotencyKey)
                    .orElseThrow(() -> conflict("幂等请求正在提交，请稍后重试。"));
            return detail(userId, winner);
        }
        repository.createTask(
                taskId,
                userId,
                nullableLong(request.get("projectId")),
                nullableLong(request.get("conversationId")),
                title,
                goal,
                criteria,
                constraints,
                templateId,
                autonomyLevel >= 2 ? "WRITE_LOW" : "READ",
                autonomyLevel
        );
        events.publish(taskId, "agent_task_created", Map.of("status", AgentTaskStatus.DRAFT.name()));
        return detail(userId, taskId);
    }

    @Transactional
    public Map<String, Object> createDelegatedRun(
            long userId,
            long missionId,
            long occurrenceId,
            int grantVersion,
            Map<String, Object> missionPayload,
            MissionRepository missionRepository
    ) {
        String key = "mission-occurrence-" + occurrenceId;
        Map<String, Object> request = new LinkedHashMap<>(missionPayload);
        request.put("templateId", agentTaskTemplate(text(missionPayload.get("templateId"))));
        request.putIfAbsent("autonomyLevel", 1);
        Map<String, Object> created = create(userId, key, request);
        long taskId = longValue(created.get("id"));
        missionRepository.linkDelegatedTask(taskId, missionId, occurrenceId, grantVersion);

        Map<String, Object> planned = created;
        if ("DRAFT".equals(text(created.get("status")))) {
            planned = generatePlan(
                    userId,
                    taskId,
                    longValue(created.get("version")),
                    key + "-plan"
            );
        }
        int planVersion = integer(planned.get("activePlanVersion"), 0);
        if (planVersion <= 0) throw conflict("持续任务未生成有效计划。");

        Map<String, Object> approved = planned;
        if ("AWAITING_PLAN_APPROVAL".equals(text(planned.get("status")))) {
            approved = approvePlan(
                    userId,
                    taskId,
                    planVersion,
                    longValue(planned.get("version")),
                    key + "-approve"
            );
        }
        if ("READY".equals(text(approved.get("status")))) {
            return start(
                    userId,
                    taskId,
                    longValue(approved.get("version")),
                    key + "-start"
            );
        }
        return approved;
    }

    public Map<String, Object> list(long userId, String status, int limit) {
        List<Map<String, Object>> items = repository.listTasks(userId, status, limit).stream()
                .map(this::taskListItem)
                .toList();
        return Map.of("items", items);
    }

    public Map<String, Object> detail(long userId, long taskId) {
        TaskRecord task = requireTask(userId, taskId);
        Map<String, Object> result = taskSummary(task);
        if (task.activePlanVersion() != null) {
            repository.findPlan(task.id(), task.activePlanVersion()).ifPresent(plan -> result.put("plan", planMap(plan)));
        }
        if (task.currentRunId() != null) {
            repository.findRun(task.currentRunId()).ifPresent(run -> {
                result.put("run", runMap(run));
                result.put("stepRuns", repository.listStepRuns(run.id()));
                result.put("subtaskGroups", repository.listSubtaskGroups(run.id()));
                repository.findPendingPreview(run.id()).ifPresent(preview -> result.put("pendingChangePreview", previewMap(preview)));
            });
        }
        result.put("artifacts", repository.listArtifacts(task.id()));
        result.put("availableActions", availableActions(task));
        return result;
    }

    @Transactional
    public Map<String, Object> generatePlan(long userId, long taskId, long expectedVersion, String idempotencyKey) {
        TaskRecord task = requireTask(userId, taskId);
        if (!reserveMutation(userId, taskId, "PLAN_GENERATE", idempotencyKey)) return detail(userId, taskId);
        requireVersion(task, expectedVersion);
        stateMachine.requireTransition(task.status(), AgentTaskStatus.PLANNING);
        if (!repository.transitionTask(task.id(), expectedVersion, task.status(), AgentTaskStatus.PLANNING)) conflict();
        events.publish(task.id(), "task.status.changed", Map.of("status", AgentTaskStatus.PLANNING.name()));
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("taskId", String.valueOf(task.id()));
            request.put("userId", String.valueOf(userId));
            request.put("goal", task.goal());
            request.put("completionCriteria", task.completionCriteria());
            request.put("constraints", task.constraints());
            request.put("templateId", task.templateId());
            request.put("autonomyLevel", task.autonomyLevel());
            request.put("projectId", task.projectId());
            request.put("conversationId", task.conversationId());
            request.put("budget", AgentBudget.DEFAULT.toMap());
            request.put("toolCatalog", toolCatalog(task.autonomyLevel(), task.constraints()));
            Map<String, Object> generated = decisionClient.plan(request);
            List<Map<String, Object>> steps = mapList(generated.get("steps"));
            if (steps.isEmpty()) throw new IllegalStateException("Planner 没有返回可执行步骤。");
            validateSteps(steps, task.autonomyLevel(), task.constraints(), task.projectId());
            Map<String, Object> accessScope = map(generated.get("accessScope"));
            accessScope.put("allowedTools", steps.stream().map(step -> text(step.get("toolRef"))).filter(value -> !value.isBlank()).distinct().toList());
            AgentBudget budget = AgentBudget.from(map(generated.getOrDefault("budget", AgentBudget.DEFAULT.toMap())));
            int planVersion = task.activePlanVersion() == null ? 1 : task.activePlanVersion() + 1;
            repository.insertPlan(task.id(), planVersion, "MODEL", task.goal(), task.completionCriteria(),
                    task.constraints(), accessScope, budget, Map.of("reason", "初始计划"), steps);
            TaskRecord afterPlan = requireTask(userId, taskId);
            if (!repository.transitionTask(afterPlan.id(), afterPlan.version(), AgentTaskStatus.PLANNING, AgentTaskStatus.AWAITING_PLAN_APPROVAL)) conflict();
            events.publish(task.id(), "plan.generated", Map.of("planVersion", planVersion, "stepCount", steps.size()));
            events.publish(task.id(), "plan.approval.required", Map.of("planVersion", planVersion));
            return detail(userId, taskId);
        } catch (RuntimeException exc) {
            repository.transitionTaskFromCurrent(task.id(), AgentTaskStatus.PLANNING, AgentTaskStatus.DRAFT);
            events.publish(task.id(), "plan.failed", Map.of("errorCode", "PLANNER_ERROR"));
            throw exc;
        }
    }

    @Transactional
    public Map<String, Object> updatePlan(long userId, long taskId, int version, long expectedVersion,
                                          Map<String, Object> request, String idempotencyKey) {
        TaskRecord task = requireTask(userId, taskId);
        if (!reserveMutation(userId, taskId, "PLAN_UPDATE:" + version, idempotencyKey)) return detail(userId, taskId);
        requireVersion(task, expectedVersion);
        if (task.status() != AgentTaskStatus.AWAITING_PLAN_APPROVAL || task.activePlanVersion() == null || task.activePlanVersion() != version) {
            throw conflict("只有当前待批准计划可以编辑。");
        }
        PlanRecord previous = repository.findPlan(taskId, version).orElseThrow(() -> notFound("计划不存在。"));
        List<Map<String, Object>> steps = mapList(request.get("steps"));
        if (steps.isEmpty()) steps = previous.steps().stream().map(this::stepMap).toList();
        validateSteps(steps, task.autonomyLevel(), task.constraints(), task.projectId());
        AgentBudget budget = request.containsKey("budget") ? AgentBudget.from(map(request.get("budget"))) : previous.budget();
        requireBudgetNotIncreased(previous.budget(), budget);
        Map<String, Object> accessScope = new LinkedHashMap<>(previous.accessScope());
        accessScope.put("allowedTools", steps.stream().map(step -> text(step.get("toolRef"))).filter(value -> !value.isBlank()).distinct().toList());
        int nextVersion = version + 1;
        repository.insertPlan(taskId, nextVersion, "USER", task.goal(), task.completionCriteria(), task.constraints(),
                accessScope, budget, Map.of("reason", "用户编辑计划", "fromVersion", version), steps);
        events.publish(taskId, "agent_plan_edited", Map.of("planVersion", nextVersion, "fromVersion", version));
        return detail(userId, taskId);
    }

    @Transactional
    public Map<String, Object> approvePlan(long userId, long taskId, int version, long expectedVersion, String idempotencyKey) {
        TaskRecord task = requireTask(userId, taskId);
        if (!reserveMutation(userId, taskId, "PLAN_APPROVE:" + version, idempotencyKey)) return detail(userId, taskId);
        requireVersion(task, expectedVersion);
        if (task.status() != AgentTaskStatus.AWAITING_PLAN_APPROVAL || task.activePlanVersion() == null || task.activePlanVersion() != version) {
            throw conflict("计划版本已变化，请刷新后重新审批。");
        }
        PlanRecord plan = repository.findPlan(taskId, version).orElseThrow(() -> notFound("计划不存在。"));
        repository.approvePlan(plan.id(), userId);
        Map<String, Object> permissions = new LinkedHashMap<>(plan.accessScope());
        permissions.put("approvedPlanVersion", version);
        permissions.put("approvedBy", userId);
        long runId = repository.createRun(taskId, version, permissions, plan.budget());
        repository.createCheckpoint(runId, "PLAN_APPROVED", version, null, com.example.aiapp.agenttask.domain.AgentBudgetUsage.zero());
        if (!repository.transitionTask(taskId, expectedVersion, AgentTaskStatus.AWAITING_PLAN_APPROVAL, AgentTaskStatus.READY)) conflict();
        events.publish(taskId, "agent_plan_approved", Map.of("planVersion", version, "runId", runId));
        events.publish(taskId, "task.status.changed", Map.of("status", AgentTaskStatus.READY.name()));
        return detail(userId, taskId);
    }

    @Transactional
    public Map<String, Object> start(long userId, long taskId, long expectedVersion, String idempotencyKey) {
        TaskRecord task = requireTask(userId, taskId);
        if (!reserveMutation(userId, taskId, "START", idempotencyKey)) return detail(userId, taskId);
        requireVersion(task, expectedVersion);
        if (task.autonomyLevel() == 0) throw conflict("该任务为仅制定计划模式，不能启动执行。" );
        if (task.currentRunId() == null) throw conflict("任务尚未创建运行记录。");
        PlanRecord plan = repository.findPlan(task.id(), task.activePlanVersion())
                .orElseThrow(() -> conflict("当前计划不存在。"));
        RunRecord readyRun = repository.findRun(task.currentRunId()).orElseThrow(() -> conflict("运行记录不存在。"));
        if (!featureFlags.allows(task, plan, readyRun.permissionSnapshot())) {
            throw conflict("动态 Agent 执行已由 Feature Flag 关闭；仍可查看或编辑计划。" );
        }
        stateMachine.requireTransition(task.status(), AgentTaskStatus.RUNNING);
        if (!repository.transitionTask(taskId, expectedVersion, task.status(), AgentTaskStatus.RUNNING)) conflict();
        if (!repository.updateRunStatus(task.currentRunId(), "READY", "RUNNING")) conflict("运行状态已变化。");
        events.publish(taskId, "agent_task_started", Map.of("runId", task.currentRunId()));
        enqueueAfterCommit(task.currentRunId());
        return detail(userId, taskId);
    }

    @Transactional
    public Map<String, Object> pause(long userId, long taskId, long expectedVersion, String idempotencyKey) {
        TaskRecord task = requireTask(userId, taskId);
        if (!reserveMutation(userId, taskId, "PAUSE", idempotencyKey)) return detail(userId, taskId);
        requireVersion(task, expectedVersion);
        if (task.status() != AgentTaskStatus.RUNNING || task.currentRunId() == null) throw conflict("任务当前不可暂停。");
        if (!repository.transitionTask(taskId, expectedVersion, AgentTaskStatus.RUNNING, AgentTaskStatus.PAUSING)) conflict();
        repository.updateRunStatus(task.currentRunId(), "RUNNING", "PAUSING");
        events.publish(taskId, "agent_task_paused", Map.of("status", AgentTaskStatus.PAUSING.name()));
        enqueueAfterCommit(task.currentRunId());
        return detail(userId, taskId);
    }

    @Transactional
    public Map<String, Object> resume(long userId, long taskId, long expectedVersion, String idempotencyKey) {
        TaskRecord task = requireTask(userId, taskId);
        if (!reserveMutation(userId, taskId, "RESUME", idempotencyKey)) return detail(userId, taskId);
        requireVersion(task, expectedVersion);
        if (task.currentRunId() == null) throw conflict("任务没有可恢复的运行记录。");
        if (task.status() == AgentTaskStatus.AWAITING_CONFIRMATION) {
            reconcileConfirmation(task);
            TaskRecord refreshed = requireTask(userId, taskId);
            if (refreshed.status() == AgentTaskStatus.RUNNING) enqueueAfterCommit(refreshed.currentRunId());
            return detail(userId, taskId);
        }
        if (task.status() != AgentTaskStatus.PAUSED && task.status() != AgentTaskStatus.AWAITING_INPUT) {
            throw conflict("任务当前不可继续。");
        }
        Map<String, Object> resumeCheck = validateResumeState(task);
        if (!bool(resumeCheck.get("valid")) || bool(resumeCheck.get("changed"))) {
            if (task.status() == AgentTaskStatus.PAUSED) {
                if (!repository.transitionTask(taskId, expectedVersion, AgentTaskStatus.PAUSED, AgentTaskStatus.AWAITING_INPUT)) conflict();
                repository.updateRunStatus(task.currentRunId(), "PAUSED", "AWAITING_INPUT");
            }
            events.publish(taskId, "resume.validation.required", Map.of(
                    "reason", textOr(resumeCheck.get("reason"), "恢复前环境已变化。"),
                    "changed", bool(resumeCheck.get("changed"))
            ));
            return detail(userId, taskId);
        }
        if (!repository.transitionTask(taskId, expectedVersion, task.status(), AgentTaskStatus.RUNNING)) conflict();
        repository.updateRunStatus(task.currentRunId(), task.status().name(), "RUNNING");
        events.publish(taskId, "agent_task_resumed", Map.of("runId", task.currentRunId()));
        enqueueAfterCommit(task.currentRunId());
        return detail(userId, taskId);
    }

    @Transactional
    public Map<String, Object> cancel(long userId, long taskId, long expectedVersion, String idempotencyKey) {
        TaskRecord task = requireTask(userId, taskId);
        if (!reserveMutation(userId, taskId, "CANCEL", idempotencyKey)) return detail(userId, taskId);
        requireVersion(task, expectedVersion);
        if (task.status().isTerminal()) throw conflict("终态任务不可再次终止。");
        stateMachine.requireTransition(task.status(), AgentTaskStatus.CANCELLED);
        if (!repository.transitionTask(taskId, expectedVersion, task.status(), AgentTaskStatus.CANCELLED)) conflict();
        if (task.currentRunId() != null) repository.updateRunStatus(task.currentRunId(), task.status().name(), "CANCELLED");
        events.publish(taskId, "agent_task_cancelled", Map.of("partialResultsPreserved", true));
        return detail(userId, taskId);
    }

    @Transactional
    public Map<String, Object> provideInput(long userId, long taskId, long expectedVersion,
                                             Map<String, Object> request, String idempotencyKey) {
        TaskRecord task = requireTask(userId, taskId);
        if (!reserveMutation(userId, taskId, "PROVIDE_INPUT", idempotencyKey)) return detail(userId, taskId);
        requireVersion(task, expectedVersion);
        if (task.status() != AgentTaskStatus.AWAITING_INPUT || task.currentRunId() == null) {
            throw conflict("任务当前不等待补充信息。");
        }
        String input = requiredText(request, "input", 4_000);
        String action = textOr(request.get("action"), "RETRY").toUpperCase();
        if (!List.of("RETRY", "SKIP", "REVISE_PLAN", "TERMINATE").contains(action)) {
            throw badRequest("action 只支持 RETRY、SKIP、REVISE_PLAN 或 TERMINATE。");
        }
        repository.saveInput(task.id(), task.currentRunId(), action, input);
        events.publish(taskId, "input.provided", Map.of("length", input.length(), "action", action));
        if (action.equals("TERMINATE")) {
            if (!repository.transitionTask(taskId, expectedVersion, AgentTaskStatus.AWAITING_INPUT, AgentTaskStatus.CANCELLED)) conflict();
            repository.updateRunStatus(task.currentRunId(), "AWAITING_INPUT", "CANCELLED");
            events.publish(taskId, "agent_task_cancelled", Map.of("partialResultsPreserved", true, "source", "USER_INPUT"));
            return detail(userId, taskId);
        }
        if (action.equals("REVISE_PLAN")) return revisePlanFromInput(task, input);
        if (action.equals("SKIP")) repository.skipLatestFailedStep(task.currentRunId());
        if (action.equals("RETRY")) repository.retryLatestWaitingStep(task.currentRunId());
        if (!repository.transitionTask(taskId, expectedVersion, AgentTaskStatus.AWAITING_INPUT, AgentTaskStatus.RUNNING)) conflict();
        repository.updateRunStatus(task.currentRunId(), "AWAITING_INPUT", "RUNNING");
        enqueueAfterCommit(task.currentRunId());
        return detail(userId, taskId);
    }

    public List<Map<String, Object>> artifacts(long userId, long taskId) {
        requireTask(userId, taskId);
        return repository.listArtifacts(taskId);
    }

    public List<Map<String, Object>> audit(long userId, long taskId, int limit) {
        requireTask(userId, taskId);
        return repository.listAudit(taskId, limit);
    }

    public void requireAccess(long userId, long taskId) {
        requireTask(userId, taskId);
    }

    private void reconcileConfirmation(TaskRecord task) {
        RunRecord run = repository.findRun(task.currentRunId()).orElseThrow(() -> conflict("运行记录不存在。"));
        ChangePreviewRecord preview = repository.findPendingPreview(run.id()).orElseThrow(() -> conflict("待确认变更不存在。"));
        if (preview.pendingOperationId() == null) throw conflict("待确认操作尚未创建。" );
        Map<String, Object> external = decisionClient.operationStatus(task.userId(), preview.pendingOperationId());
        String status = text(external.get("status")).toUpperCase();
        if (status.equals("PENDING")) throw conflict("写操作尚未确认或拒绝。");
        Map<String, Object> operationResult = map(external.get("result"));
        if (status.equals("CONFIRMED") && bool(operationResult.get("ok"))) {
            com.example.aiapp.agenttask.domain.AgentBudgetUsage budget = repository.settleBudget(
                    run.id(), preview.stepRunId(), 1, 0, 0, 0, run.noProgressCount()
            ).orElse(null);
            if (budget == null) {
                failConfirmedWrite(task, run, preview, operationResult, "BUDGET_SETTLEMENT_REJECTED");
                return;
            }
            repository.resolvePreview(preview.id(), "APPLIED");
            repository.finishStep(preview.stepRunId(), AgentStepStatus.SUCCEEDED, operationResult,
                    "pending-operation://" + preview.pendingOperationId(), null, null);
            List<Map<String, Object>> criterionEvidence = writeCriterionEvidence(task, preview);
            repository.createArtifact(task.id(), run.id(), preview.stepRunId(), "CHANGE", "已确认的写操作",
                    textOr(operationResult.get("userMessage"), textOr(operationResult.get("content"), "写操作执行成功。")),
                    "pending-operation://" + preview.pendingOperationId(), list(operationResult.get("evidenceRefs")),
                    Map.of("operationResult", operationResult, "criterionEvidence", criterionEvidence));
            repository.createCheckpoint(run.id(), "WRITE_APPLIED", run.planVersion(), preview.stepRunId(), budget);
            repository.updateRunStatus(run.id(), "AWAITING_CONFIRMATION", "RUNNING");
            if (!repository.transitionTaskFromCurrent(task.id(), AgentTaskStatus.AWAITING_CONFIRMATION, AgentTaskStatus.RUNNING)) conflict();
            events.publish(task.id(), "agent_confirmation_resolved", Map.of("outcome", "CONFIRMED", "previewId", preview.id()));
            return;
        }
        String errorCode = status.equals("REJECTED") ? "USER_REJECTED" : "WRITE_EXECUTION_FAILED";
        failConfirmedWrite(task, run, preview, operationResult, errorCode);
    }

    private void enqueueAfterCommit(long runId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            worker.enqueue(runId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                worker.enqueue(runId);
            }
        });
    }

    private void failConfirmedWrite(TaskRecord task, RunRecord run, ChangePreviewRecord preview,
                                    Map<String, Object> operationResult, String errorCode) {
        repository.resolvePreview(preview.id(), errorCode.equals("USER_REJECTED") ? "REJECTED" : "FAILED");
        String summary = errorCode.equals("USER_REJECTED")
                ? "用户拒绝该写操作。"
                : textOr(operationResult.get("message"), "写操作真实执行失败，未将步骤记为成功。");
        Map<String, Object> output = new LinkedHashMap<>(operationResult);
        output.put("summary", summary);
        repository.finishStep(preview.stepRunId(), AgentStepStatus.FAILED, output,
                "pending-operation://" + preview.pendingOperationId(), null, errorCode);
        repository.createCheckpoint(run.id(), "WRITE_FAILED", run.planVersion(), preview.stepRunId(), run.budgetUsed());
        repository.updateRunStatus(run.id(), "AWAITING_CONFIRMATION", "AWAITING_INPUT");
        if (!repository.transitionTaskFromCurrent(task.id(), AgentTaskStatus.AWAITING_CONFIRMATION, AgentTaskStatus.AWAITING_INPUT)) conflict();
        events.publish(task.id(), "agent_confirmation_resolved", Map.of(
                "outcome", errorCode, "previewId", preview.id(), "reason", summary));
        events.publish(task.id(), "input.required", Map.of(
                "errorCode", errorCode,
                "reason", summary,
                "options", List.of("修复后继续", "跳过并部分完成", "调整计划", "终止")
        ));
    }

    private static List<Map<String, Object>> writeCriterionEvidence(TaskRecord task, ChangePreviewRecord preview) {
        String action = preview.actionType().toLowerCase();
        String ref = "pending-operation://" + preview.pendingOperationId();
        List<Map<String, Object>> mappings = new ArrayList<>();
        for (Object raw : task.completionCriteria()) {
            String criterion;
            if (raw instanceof Map<?, ?> values) {
                Object value = values.get("criterion");
                if (value == null) value = values.get("title");
                if (value == null) value = values.get("description");
                criterion = text(value == null ? values : value);
            } else {
                criterion = text(raw);
            }
            String lowered = criterion.toLowerCase();
            boolean supported = (action.startsWith("todo.")
                    && List.of("任务", "待办", "todo", "草稿").stream().anyMatch(lowered::contains))
                    || (action.equals("file.patch")
                    && List.of("文件", "patch", "文档", "修改").stream().anyMatch(lowered::contains));
            if (supported) {
                mappings.add(Map.of(
                        "criterion", criterion,
                        "status", "COMPLETE",
                        "evidenceRefs", List.of(ref),
                        "note", "写操作已通过独立确认链路真实执行并记录幂等结果。"
                ));
            }
        }
        return mappings;
    }

    private Map<String, Object> revisePlanFromInput(TaskRecord task, String input) {
        RunRecord run = repository.findRun(task.currentRunId()).orElseThrow(() -> conflict("运行记录不存在。"));
        PlanRecord previous = repository.findPlan(task.id(), run.planVersion()).orElseThrow(() -> conflict("原计划不存在。"));
        List<Map<String, Object>> stepRuns = repository.listStepRuns(run.id());
        List<String> completedTools = stepRuns.stream()
                .filter(item -> "SUCCEEDED".equals(text(item.get("status"))))
                .map(item -> text(item.get("toolRef"))).filter(value -> !value.isBlank()).distinct().toList();
        List<String> skippedTools = stepRuns.stream()
                .filter(item -> List.of("FAILED", "CANCELLED").contains(text(item.get("status"))))
                .map(item -> text(item.get("toolRef"))).filter(value -> !value.isBlank()).distinct().toList();
        Map<String, Object> constraints = new LinkedHashMap<>(task.constraints());
        constraints.put("userRevision", input);
        constraints.put("completedToolRefs", completedTools);
        constraints.put("skipToolRefs", skippedTools);
        AgentBudget remainingBudget = remainingBudget(previous.budget(), run.budgetUsed());
        Map<String, Object> request = plannerRequest(task, constraints, remainingBudget);
        Map<String, Object> generated = decisionClient.plan(request);
        List<Map<String, Object>> steps = mapList(generated.get("steps"));
        if (steps.isEmpty()) throw conflict("调整后没有剩余可执行步骤，请选择终止或修改补充信息。");
        validateSteps(steps, task.autonomyLevel(), constraints, task.projectId());
        AgentBudget budget = AgentBudget.from(map(generated.getOrDefault("budget", remainingBudget.toMap())));
        requireBudgetNotIncreased(remainingBudget, budget);
        Map<String, Object> accessScope = map(generated.get("accessScope"));
        accessScope.put("allowedTools", steps.stream().map(step -> text(step.get("toolRef"))).filter(value -> !value.isBlank()).distinct().toList());
        int nextVersion = previous.version() + 1;
        repository.insertPlan(task.id(), nextVersion, "MODEL", task.goal(), task.completionCriteria(),
                constraints, accessScope, budget,
                Map.of("reason", "用户要求调整执行路径", "fromVersion", previous.version(), "input", input), steps);
        repository.updateRunStatus(run.id(), "AWAITING_INPUT", "PARTIALLY_COMPLETED");
        if (!repository.transitionTaskFromCurrent(task.id(), AgentTaskStatus.AWAITING_INPUT, AgentTaskStatus.AWAITING_PLAN_APPROVAL)) conflict();
        events.publish(task.id(), "plan.revised", Map.of(
                "planVersion", nextVersion, "fromVersion", previous.version(), "stepCount", steps.size()));
        events.publish(task.id(), "plan.approval.required", Map.of("planVersion", nextVersion));
        return detail(task.userId(), task.id());
    }

    private Map<String, Object> validateResumeState(TaskRecord task) {
        RunRecord run = repository.findRun(task.currentRunId()).orElseThrow(() -> conflict("运行记录不存在。"));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("taskId", String.valueOf(task.id()));
        request.put("userId", String.valueOf(task.userId()));
        request.put("permissionSnapshot", run.permissionSnapshot());
        request.put("expectedExternalState", repository.latestExternalState(run.id()));
        return decisionClient.resumeCheck(request);
    }

    private Map<String, Object> plannerRequest(TaskRecord task, Map<String, Object> constraints, AgentBudget budget) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("taskId", String.valueOf(task.id()));
        request.put("userId", String.valueOf(task.userId()));
        request.put("goal", task.goal());
        request.put("completionCriteria", task.completionCriteria());
        request.put("constraints", constraints);
        request.put("templateId", task.templateId());
        request.put("autonomyLevel", task.autonomyLevel());
        request.put("projectId", task.projectId());
        request.put("conversationId", task.conversationId());
        request.put("budget", budget.toMap());
        request.put("toolCatalog", toolCatalog(task.autonomyLevel(), constraints));
        return request;
    }

    private static AgentBudget remainingBudget(AgentBudget limit, com.example.aiapp.agenttask.domain.AgentBudgetUsage used) {
        int steps = limit.maxSteps() - used.completedSteps();
        int calls = limit.maxToolCalls() - used.toolCalls();
        long tokens = limit.maxModelTokens() - used.modelTokens();
        long cost = limit.maxCostMicros() - used.costMicros();
        long wallTime = limit.maxWallTimeMillis() - used.elapsedMillis();
        if (steps <= 0 || calls <= 0 || tokens <= 0 || cost <= 0 || wallTime <= 0) {
            throw conflict("累计硬预算已经耗尽，不能通过改计划重新获得预算。");
        }
        return new AgentBudget(steps, calls, tokens, cost, wallTime,
                limit.maxRetriesPerStep(), limit.maxConsecutiveNoProgress());
    }

    private boolean reserveMutation(long userId, long taskId, String action, String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);
        String actionKey = action + ":" + taskId;
        Long existing = repository.findIdempotentTask(userId, actionKey, idempotencyKey).orElse(null);
        if (existing != null) {
            if (existing != taskId) throw conflict("Idempotency-Key 已用于其他任务。");
            return false;
        }
        if (repository.reserveIdempotency(userId, actionKey, idempotencyKey, taskId)) return true;
        Long winner = repository.findIdempotentTask(userId, actionKey, idempotencyKey).orElse(null);
        if (winner != null && winner == taskId) return false;
        throw conflict("幂等请求正在提交，请稍后重试。");
    }

    private static void validateIdempotencyKey(String value) {
        if (value == null || value.isBlank()) throw badRequest("Idempotency-Key 不能为空。");
        if (value.length() > 128) throw badRequest("Idempotency-Key 过长。");
    }

    private TaskRecord requireTask(long userId, long taskId) {
        return repository.findTask(taskId, userId).orElseThrow(() -> notFound("任务不存在。"));
    }

    private Map<String, Object> taskSummary(TaskRecord task) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", task.id());
        result.put("title", task.title());
        result.put("goal", task.goal());
        result.put("completionCriteria", task.completionCriteria());
        result.put("constraints", task.constraints());
        result.put("projectId", task.projectId());
        result.put("conversationId", task.conversationId());
        result.put("templateId", task.templateId());
        result.put("status", task.status().name());
        result.put("riskLevel", task.riskLevel());
        result.put("autonomyLevel", task.autonomyLevel());
        result.put("activePlanVersion", task.activePlanVersion());
        result.put("currentRunId", task.currentRunId());
        result.put("version", task.version());
        result.put("createdAt", iso(task.createdAt()));
        result.put("updatedAt", iso(task.updatedAt()));
        return result;
    }

    private Map<String, Object> taskListItem(TaskRecord task) {
        Map<String, Object> result = taskSummary(task);
        if (task.activePlanVersion() != null) {
            repository.findPlan(task.id(), task.activePlanVersion())
                    .ifPresent(plan -> result.put("plan", planMap(plan)));
        }
        if (task.currentRunId() != null) {
            repository.findRun(task.currentRunId())
                    .ifPresent(run -> result.put("run", runMap(run)));
        }
        return result;
    }

    private Map<String, Object> planMap(PlanRecord plan) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", plan.id());
        result.put("version", plan.version());
        result.put("source", plan.source());
        result.put("goal", plan.goal());
        result.put("completionCriteria", plan.completionCriteria());
        result.put("constraints", plan.constraints());
        result.put("accessScope", plan.accessScope());
        result.put("budget", plan.budget().toMap());
        result.put("changeSummary", plan.changeSummary());
        result.put("approvalStatus", plan.approvalStatus());
        result.put("approvedBy", plan.approvedBy());
        result.put("approvedAt", iso(plan.approvedAt()));
        result.put("steps", plan.steps().stream().map(this::stepMap).toList());
        return result;
    }

    private Map<String, Object> stepMap(PlanStepRecord step) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", step.id());
        result.put("stepKey", step.stepKey());
        result.put("position", step.position());
        result.put("stepType", step.stepType());
        result.put("title", step.title());
        result.put("purpose", step.purpose());
        result.put("toolRef", step.toolRef());
        result.put("inputTemplate", step.inputTemplate());
        result.put("dependencies", step.dependencies());
        result.put("riskLevel", step.riskLevel());
        result.put("expectedArtifact", step.expectedArtifact());
        result.put("writeIntent", step.writeIntent());
        return result;
    }

    private Map<String, Object> runMap(RunRecord run) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", run.id());
        result.put("planVersion", run.planVersion());
        result.put("status", run.status());
        result.put("budgetLimit", run.budgetLimit().toMap());
        result.put("budgetUsed", run.budgetUsed().toMap());
        result.put("noProgressCount", run.noProgressCount());
        result.put("startedAt", iso(run.startedAt()));
        result.put("pausedAt", iso(run.pausedAt()));
        result.put("finishedAt", iso(run.finishedAt()));
        result.put("updatedAt", iso(run.updatedAt()));
        return result;
    }

    private Map<String, Object> previewMap(ChangePreviewRecord preview) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", preview.id());
        result.put("stepRunId", preview.stepRunId());
        result.put("pendingOperationId", preview.pendingOperationId());
        result.put("resourceRef", preview.resourceRef());
        result.put("actionType", preview.actionType());
        result.put("before", preview.before());
        result.put("after", preview.after());
        result.put("impact", preview.impact());
        result.put("riskLevel", preview.riskLevel());
        result.put("undoSupported", preview.undoSupported());
        result.put("status", preview.status());
        result.put("createdAt", iso(preview.createdAt()));
        return result;
    }

    private static List<String> availableActions(TaskRecord task) {
        return switch (task.status()) {
            case DRAFT -> List.of("GENERATE_PLAN", "CANCEL");
            case AWAITING_PLAN_APPROVAL -> List.of("EDIT_PLAN", "APPROVE_PLAN", "CANCEL");
            case READY -> task.autonomyLevel() == 0 ? List.of("CANCEL") : List.of("START", "CANCEL");
            case RUNNING -> List.of("PAUSE", "CANCEL");
            case PAUSING -> List.of("CANCEL");
            case PAUSED -> List.of("RESUME", "CANCEL");
            case AWAITING_CONFIRMATION -> List.of("RESUME", "CANCEL");
            case AWAITING_INPUT -> List.of("PROVIDE_INPUT", "RESUME", "CANCEL");
            default -> List.of();
        };
    }

    private static List<Map<String, Object>> toolCatalog(
            int autonomyLevel,
            Map<String, Object> constraints
    ) {
        boolean delegated = constraints.containsKey("delegatedAllowedTools");
        Set<String> delegatedAllowed = new LinkedHashSet<>();
        if (delegated) {
            list(constraints.get("delegatedAllowedTools")).stream()
                    .map(String::valueOf)
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .forEach(delegatedAllowed::add);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        AgentPolicyEngine.READ_TOOLS.stream()
                .filter(ref -> !delegated || delegatedAllowed.contains(ref))
                .forEach(ref -> result.add(Map.of(
                        "toolRef", ref,
                        "riskLevel", ref.equals("research.search") ? "EXTERNAL_READ" : "READ"
                )));
        if (autonomyLevel >= 2) {
            AgentPolicyEngine.WRITE_TOOLS.stream()
                    .filter(ref -> !delegated || delegatedAllowed.contains(ref))
                    .forEach(ref -> result.add(Map.of("toolRef", ref, "riskLevel", "WRITE_LOW")));
        }
        return result;
    }

    private static String agentTaskTemplate(String missionTemplate) {
        return switch (missionTemplate) {
            case "project-health" -> "project-inspection";
            case "research-weekly" -> "research";
            case "release-readiness" -> "release-check";
            default -> missionTemplate;
        };
    }

    private static void validateSteps(
            List<Map<String, Object>> steps,
            int autonomyLevel,
            Map<String, Object> constraints,
            Long projectId
    ) {
        if (steps.isEmpty() || steps.size() > AgentBudget.HARD_LIMIT.maxSteps()) {
            throw badRequest("计划步骤数量无效。");
        }
        Set<String> delegatedAllowed = constraints.containsKey("delegatedAllowedTools")
                ? list(constraints.get("delegatedAllowedTools")).stream()
                        .map(String::valueOf)
                        .map(String::trim)
                        .filter(value -> !value.isBlank())
                        .collect(java.util.stream.Collectors.toSet())
                : null;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Map<String, Object> step : steps) {
            String key = text(step.get("stepKey"));
            String toolRef = text(step.get("toolRef"));
            boolean writeIntent = step.get("writeIntent") instanceof Boolean value && value;
            String riskLevel = text(step.get("riskLevel")).toUpperCase();
            if (key.isBlank() || !seen.add(key)) throw badRequest("计划步骤 key 不能为空或重复。");
            if (delegatedAllowed != null && !delegatedAllowed.contains(toolRef)) {
                throw badRequest("计划包含委托授权之外的工具。");
            }
            if (writeIntent) {
                if (autonomyLevel < 2 || !AgentPolicyEngine.WRITE_TOOLS.contains(toolRef) || !riskLevel.equals("WRITE_LOW")) {
                    throw badRequest("计划包含未授权的写步骤。");
                }
            } else if (!AgentPolicyEngine.READ_TOOLS.contains(toolRef) || !List.of("READ", "EXTERNAL_READ").contains(riskLevel)) {
                throw badRequest("计划包含未授权的只读步骤。");
            }
            AgentConstraintPolicy.violation(toolRef, map(step.get("inputTemplate")), constraints, projectId)
                    .ifPresent(reason -> { throw badRequest("计划违反禁止事项：" + reason); });
            for (Object dependency : list(step.get("dependencies"))) {
                if (!seen.contains(String.valueOf(dependency))) {
                    throw badRequest("步骤依赖必须引用已出现的前置步骤。");
                }
            }
        }
    }

    private static void requireBudgetNotIncreased(AgentBudget previous, AgentBudget next) {
        if (next.maxSteps() > previous.maxSteps()
                || next.maxToolCalls() > previous.maxToolCalls()
                || next.maxModelTokens() > previous.maxModelTokens()
                || next.maxCostMicros() > previous.maxCostMicros()
                || next.maxWallTimeMillis() > previous.maxWallTimeMillis()
                || next.maxRetriesPerStep() > previous.maxRetriesPerStep()
                || next.maxConsecutiveNoProgress() > previous.maxConsecutiveNoProgress()) {
            throw badRequest("用户只能调低计划预算，不能超过上一版本。");
        }
    }

    private static void requireVersion(TaskRecord task, long expectedVersion) {
        if (task.version() != expectedVersion) throw conflict("任务已被其他操作更新，请刷新后重试。");
    }

    private static String requiredText(Map<String, Object> request, String key, int maxLength) {
        String value = text(request.get(key));
        if (value.isBlank()) throw badRequest(key + " 不能为空。");
        if (value.length() > maxLength) throw badRequest(key + " 过长。");
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> source ? new LinkedHashMap<>((Map<String, Object>) source) : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return value instanceof List<?> source ? new ArrayList<>((List<Object>) source) : new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> source)) return new ArrayList<>();
        return source.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) new LinkedHashMap<>((Map<String, Object>) item))
                .toList();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String textOr(Object value, String fallback) {
        String result = text(value);
        return result.isBlank() ? fallback : result;
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean bool && bool;
    }

    private static String nullableText(Object value) {
        String result = text(value);
        return result.isBlank() ? null : result;
    }

    private static Long nullableLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null ? 0 : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static int integer(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static String iso(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC).toString();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static void conflict() {
        throw conflict("任务状态已变化，请刷新后重试。");
    }
}
