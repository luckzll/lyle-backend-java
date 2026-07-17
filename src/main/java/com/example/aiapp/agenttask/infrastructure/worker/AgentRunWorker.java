package com.example.aiapp.agenttask.infrastructure.worker;

import com.example.aiapp.agenttask.domain.AgentBudgetUsage;
import com.example.aiapp.agenttask.config.AgentTaskFeatureFlags;
import com.example.aiapp.agenttask.domain.AgentStepStatus;
import com.example.aiapp.agenttask.domain.AgentTaskStatus;
import com.example.aiapp.agenttask.domain.budget.AgentBudgetGuard;
import com.example.aiapp.agenttask.domain.policy.AgentConstraintPolicy;
import com.example.aiapp.agenttask.domain.policy.AgentPolicyEngine;
import com.example.aiapp.agenttask.domain.security.AgentToolOutputSanitizer;
import com.example.aiapp.agenttask.infrastructure.events.AgentEventPublisher;
import com.example.aiapp.agenttask.infrastructure.python.AgentDecisionClient;
import com.example.aiapp.agenttask.infrastructure.repository.AgentTaskRepository;
import com.example.aiapp.agenttask.infrastructure.repository.AgentTaskRepository.PlanRecord;
import com.example.aiapp.agenttask.infrastructure.repository.AgentTaskRepository.PlanStepRecord;
import com.example.aiapp.agenttask.infrastructure.repository.AgentTaskRepository.RunRecord;
import com.example.aiapp.agenttask.infrastructure.repository.AgentTaskRepository.StepRunRecord;
import com.example.aiapp.agenttask.infrastructure.repository.AgentTaskRepository.TaskRecord;
import com.example.aiapp.mission.application.DelegationRuntimeGuard;
import com.example.aiapp.mission.application.DelegationRuntimeGuard.RuntimeDecision;
import com.example.aiapp.mission.infrastructure.metrics.MissionMetrics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class AgentRunWorker {

    private static final int LEASE_SECONDS = 30;

    private final AgentTaskRepository repository;
    private final AgentPolicyEngine policyEngine;
    private final AgentBudgetGuard budgetGuard;
    private final AgentDecisionClient decisions;
    private final AgentEventPublisher events;
    private final ObjectMapper objectMapper;
    private final Executor executor;
    private final Executor subtaskExecutor;
    private final ScheduledExecutorService leaseScheduler;
    private final AgentTaskFeatureFlags featureFlags;
    private final DelegationRuntimeGuard delegationGuard;
    private final int systemMaxParallelReadBranches;
    private final MissionMetrics missionMetrics;
    private final String workerId = "worker-" + UUID.randomUUID();
    private final Set<Long> queuedOrRunning = ConcurrentHashMap.newKeySet();

    @Autowired
    public AgentRunWorker(
            AgentTaskRepository repository,
            AgentPolicyEngine policyEngine,
            AgentBudgetGuard budgetGuard,
            AgentDecisionClient decisions,
            AgentEventPublisher events,
            ObjectMapper objectMapper,
            @Qualifier("agentTaskExecutor") Executor executor,
            @Qualifier("agentSubtaskExecutor") Executor subtaskExecutor,
            @Qualifier("agentLeaseScheduler") ScheduledExecutorService leaseScheduler,
            AgentTaskFeatureFlags featureFlags,
            DelegationRuntimeGuard delegationGuard,
            @Value("${app.agent-task.max-parallel-read-branches:3}") int systemMaxParallelReadBranches,
            MissionMetrics missionMetrics
    ) {
        this.repository = repository;
        this.policyEngine = policyEngine;
        this.budgetGuard = budgetGuard;
        this.decisions = decisions;
        this.events = events;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.subtaskExecutor = subtaskExecutor;
        this.leaseScheduler = leaseScheduler;
        this.featureFlags = featureFlags;
        this.delegationGuard = delegationGuard;
        this.systemMaxParallelReadBranches = Math.max(1, Math.min(systemMaxParallelReadBranches, 3));
        this.missionMetrics = missionMetrics;
    }

    public AgentRunWorker(
            AgentTaskRepository repository,
            AgentPolicyEngine policyEngine,
            AgentBudgetGuard budgetGuard,
            AgentDecisionClient decisions,
            AgentEventPublisher events,
            ObjectMapper objectMapper,
            Executor executor,
            Executor subtaskExecutor,
            ScheduledExecutorService leaseScheduler,
            AgentTaskFeatureFlags featureFlags,
            DelegationRuntimeGuard delegationGuard,
            int systemMaxParallelReadBranches
    ) {
        this(
                repository, policyEngine, budgetGuard, decisions, events, objectMapper,
                executor, subtaskExecutor, leaseScheduler, featureFlags, delegationGuard,
                systemMaxParallelReadBranches, null);
    }

    public AgentRunWorker(
            AgentTaskRepository repository,
            AgentPolicyEngine policyEngine,
            AgentBudgetGuard budgetGuard,
            AgentDecisionClient decisions,
            AgentEventPublisher events,
            ObjectMapper objectMapper,
            Executor executor,
            ScheduledExecutorService leaseScheduler,
            AgentTaskFeatureFlags featureFlags
    ) {
        this(repository, policyEngine, budgetGuard, decisions, events, objectMapper,
                executor, executor, leaseScheduler, featureFlags, null, 3, null);
    }

    public void enqueue(long runId) {
        if (!queuedOrRunning.add(runId)) return;
        executor.execute(() -> {
            try {
                advance(runId);
            } finally {
                queuedOrRunning.remove(runId);
            }
        });
    }

    @Scheduled(fixedDelayString = "${app.agent-task.recovery-scan-millis:10000}")
    public void recoverExpiredRuns() {
        repository.findRecoverableRunIds(50).forEach(this::enqueue);
    }

    private void advance(long runId) {
        for (int cycle = 0; cycle < 100; cycle++) {
            RunRecord run = repository.findRun(runId).orElse(null);
            if (run == null) return;
            TaskRecord task = repository.findTask(run.taskId()).orElse(null);
            if (task == null || task.status().isTerminal()) return;
            if (task.status() == AgentTaskStatus.PAUSING || "PAUSING".equals(run.status())) {
                completePause(task, run);
                return;
            }
            if (task.status() != AgentTaskStatus.RUNNING || !"RUNNING".equals(run.status())) return;
            PlanRecord activePlan = repository.findPlan(task.id(), run.planVersion()).orElse(null);
            if (!featureFlags.allows(task, activePlan, run.permissionSnapshot())) {
                pauseForFeatureFlag(task, run);
                return;
            }
            if (!repository.acquireLease(run.id(), workerId, LEASE_SECONDS)) return;
            try {
                if (!advanceOne(task, repository.findRun(run.id()).orElse(run))) return;
            } catch (LeaseLostException exc) {
                return;
            } catch (RuntimeException exc) {
                failTask(task, run, classifyError(exc));
                return;
            } finally {
                repository.releaseLease(run.id(), workerId);
            }
        }
        RunRecord run = repository.findRun(runId).orElse(null);
        if (run != null) {
            TaskRecord task = repository.findTask(run.taskId()).orElse(null);
            if (task != null) stopForInput(task, run, "CONTROL_LOOP_LIMIT", "执行循环达到单次 Worker 安全上限。");
        }
    }

    private boolean advanceOne(TaskRecord task, RunRecord run) {
        if (!repository.heartbeat(run.id(), workerId, LEASE_SECONDS)) {
            throw new LeaseLostException();
        }
        AgentBudgetGuard.BudgetDecision budgetDecision = budgetGuard.check(run.budgetLimit(), run.budgetUsed(), run.noProgressCount());
        if (!budgetDecision.allowed()) {
            finishWithBudgetStop(task, run, budgetDecision.exhaustedDimensions());
            return false;
        }
        if (budgetDecision.nearLimit()) {
            events.publish(task.id(), "budget.updated", Map.of("usageRatio", budgetDecision.usageRatio(), "nearLimit", true));
        }
        PlanRecord plan = repository.findPlan(task.id(), run.planVersion()).orElseThrow(() -> new IllegalStateException("有效计划不存在。"));
        StepRunRecord recovering = repository.findRunningStep(run.id()).orElse(null);
        if (recovering != null) {
            Map<String, Object> stagedResult = recovering.output();
            boolean hasStagedResult = bool(stagedResult.get("_controllerStaged"));
            boolean hasSettlement = repository.hasBudgetSettlement(recovering.id());
            if (!hasStagedResult && hasSettlement) {
                repository.finishStep(recovering.id(), AgentStepStatus.FAILED,
                        Map.of("summary", "检测到旧版本遗留步骤已完成预算结算但缺少可恢复结果；为避免重复副作用，未重新执行。"),
                        null, null, "RECOVERY_RESULT_MISSING");
                repository.createCheckpoint(run.id(), "RECOVERY_RESULT_MISSING", run.planVersion(),
                        recovering.id(), run.budgetUsed());
                stopForInput(task, run, "RECOVERY_RESULT_MISSING", "恢复结果缺失，已安全停止且不会重复执行工具。请调整计划后重试。");
                return false;
            }
            long stagedElapsedMillis = hasStagedResult
                    ? Math.max(0, longValue(stagedResult.get("_controllerElapsedMillis")))
                    : 0;
            long interruptedWallMillis = Math.max(0,
                    Duration.between(recovering.startedAt(),
                            java.time.LocalDateTime.now(java.time.Clock.systemUTC())).toMillis());
            long recoveryOnlyMillis = hasStagedResult
                    ? Math.max(0, interruptedWallMillis - stagedElapsedMillis)
                    : interruptedWallMillis;
            if (repository.chargeRecoveryElapsed(run.id(), recovering.id(), recoveryOnlyMillis).isEmpty()) {
                repository.finishStep(recovering.id(), AgentStepStatus.CANCELLED,
                        Map.of("summary", "恢复前核算发现运行步骤已超过硬墙钟预算。"),
                        null, null, "RECOVERY_WALL_BUDGET_EXCEEDED");
                finishWithBudgetStop(task, repository.findRun(run.id()).orElse(run),
                        List.of("maxWallTimeMillis"));
                return false;
            }
            if (!validateExternalState(task, run)) return false;
            PlanStepRecord recoveredStep = repository.findPlanStep(recovering.planStepId())
                    .orElseThrow(() -> new IllegalStateException("遗留运行步骤对应的计划步骤不存在。"));
            var recoveredConstraintViolation = AgentConstraintPolicy.violation(
                    recoveredStep.toolRef(), recovering.input(), task.constraints(), task.projectId());
            if (recoveredConstraintViolation.isPresent()) {
                failConstraint(task, run, recovering.id(), recoveredConstraintViolation.get());
                return false;
            }
            AgentPolicyEngine.PolicyDecision recoveredPolicy = policyEngine.evaluate(
                    recoveredStep.toolRef(), recoveredStep.riskLevel(), recoveredStep.writeIntent(),
                    run.permissionSnapshot(), recovering.input());
            if (!recoveredPolicy.allowed()) {
                repository.finishStep(recovering.id(), AgentStepStatus.FAILED,
                        Map.of("summary", recoveredPolicy.reason()), null, null, recoveredPolicy.errorCode());
                repository.createCheckpoint(run.id(), "POLICY_REJECTED", run.planVersion(), recovering.id(), run.budgetUsed());
                repository.updateRunStatus(run.id(), "RUNNING", "FAILED");
                repository.transitionTaskFromCurrent(task.id(), AgentTaskStatus.RUNNING, AgentTaskStatus.FAILED);
                events.publish(task.id(), "step.failed", Map.of(
                        "stepRunId", recovering.id(), "errorCode", recoveredPolicy.errorCode(), "policyRejected", true));
                return false;
            }
            RuntimeDecision recoveredDelegation = authorizeDelegation(
                    task, run, recovering.id(), recoveredStep, recovering.input());
            if (!recoveredDelegation.allowed()) {
                failDelegation(task, run, recovering.id(), recoveredDelegation);
                return false;
            }
            events.publish(task.id(), "step.recovery.started", Map.of(
                    "stepRunId", recovering.id(),
                    "stepKey", recoveredStep.stepKey(),
                    "attempt", recovering.attempt()
            ));
            if (!ensureBudgetReserved(task, run, recoveredStep, recovering.id(), recovering.attempt())) return false;
            RunRecord refreshed = repository.findRun(run.id()).orElse(run);
            if (recoveredDelegation.requiresConfirmation()
                    || (recoveredPolicy.requiresConfirmation() && !recoveredDelegation.autoExecuteWrite())) {
                createChangePreview(task, refreshed, recoveredStep, recovering.id(), recovering.idempotencyKey(), recovering.input());
                return false;
            }
            if (hasStagedResult) {
                events.publish(task.id(), "step.recovery.result_reused", Map.of(
                        "stepRunId", recovering.id(),
                        "budgetSettlementReused", hasSettlement
                ));
                return executeReadStep(task, refreshed, plan, recoveredStep, recovering.id(), recovering.attempt(),
                        recovering.idempotencyKey(), recovering.input(), stagedResult,
                        recoveredDelegation.autoExecuteWrite());
            }
            return executeReadStep(task, refreshed, plan, recoveredStep, recovering.id(), recovering.attempt(),
                    recovering.idempotencyKey(), recovering.input(), recoveredDelegation.autoExecuteWrite());
        }
        Boolean parallelResult = executeReadyParallelBranches(task, run, plan);
        if (parallelResult != null) return parallelResult;
        PlanStepRecord step = repository.findNextStep(run.id(), plan.id()).orElse(null);
        if (step == null) {
            finalizeTask(task, run, plan);
            return false;
        }
        Map<String, Object> stepInput = resolveInput(task, plan, step);
        var constraintViolation = AgentConstraintPolicy.violation(
                step.toolRef(), stepInput, task.constraints(), task.projectId());
        if (constraintViolation.isPresent()) {
            failConstraint(task, run, null, constraintViolation.get());
            return false;
        }
        AgentPolicyEngine.PolicyDecision policy = policyEngine.evaluate(
                step.toolRef(), step.riskLevel(), step.writeIntent(), run.permissionSnapshot(), stepInput);
        if (!policy.allowed()) {
            failPolicy(task, run, step, policy);
            return false;
        }
        RuntimeDecision delegation = authorizeDelegation(task, run, null, step, stepInput);
        if (!delegation.allowed()) {
            failDelegation(task, run, null, delegation);
            return false;
        }
        int attempt = repository.nextAttempt(run.id(), step.id());
        if (attempt > run.budgetLimit().maxRetriesPerStep() + 1) {
            stopForInput(task, run, "RETRY_LIMIT", "步骤重试次数已达到上限。");
            return false;
        }
        String idempotencyKey = sha256(task.id() + ":" + run.id() + ":" + step.id() + ":" + attempt);
        long stepRunId = repository.createStepRun(run.id(), step.id(), attempt, idempotencyKey, redactInput(stepInput));
        events.publish(task.id(), "step.started", Map.of(
                "stepRunId", stepRunId,
                "stepKey", step.stepKey(),
                "title", step.title(),
                "attempt", attempt
        ));
        if (!ensureBudgetReserved(task, run, step, stepRunId, attempt)) return false;
        RunRecord reservedRun = repository.findRun(run.id()).orElse(run);
        if (delegation.requiresConfirmation()
                || (policy.requiresConfirmation() && !delegation.autoExecuteWrite())) {
            createChangePreview(task, reservedRun, step, stepRunId, idempotencyKey, stepInput);
            return false;
        }
        return executeReadStep(
                task, reservedRun, plan, step, stepRunId, attempt, idempotencyKey, stepInput,
                delegation.autoExecuteWrite());
    }

    /**
     * Starts one bounded fan-out only when the approved DAG exposes at least
     * two independent R0 steps. The controller creates and reserves every
     * branch before dispatch, caps concurrency at three, and joins before any
     * downstream step can become eligible.
     */
    private Boolean executeReadyParallelBranches(TaskRecord task, RunRecord run, PlanRecord plan) {
        int requested = intValue(task.constraints().get("maxParallelReadBranches"));
        int maxParallelism = Math.min(systemMaxParallelReadBranches, Math.min(3, requested));
        if (maxParallelism < 2) return null;

        List<Map<String, Object>> priorRuns = repository.listStepRuns(run.id());
        Set<String> succeededKeys = new HashSet<>();
        Set<String> attemptedKeys = new HashSet<>();
        for (Map<String, Object> prior : priorRuns) {
            String key = text(prior.get("stepKey"));
            if (!key.isBlank()) attemptedKeys.add(key);
            if ("SUCCEEDED".equals(text(prior.get("status")).toUpperCase())) succeededKeys.add(key);
        }
        List<PlanStepRecord> ready = plan.steps().stream()
                .filter(step -> !attemptedKeys.contains(step.stepKey()))
                .filter(step -> !step.writeIntent())
                .filter(step -> "READ".equals(step.riskLevel()))
                .filter(step -> !"analysis.synthesize".equals(step.toolRef()))
                .filter(step -> step.dependencies().stream()
                        .map(String::valueOf)
                        .allMatch(succeededKeys::contains))
                .limit(maxParallelism)
                .toList();
        if (ready.size() < 2) return null;

        List<PreparedBranch> authorized = new ArrayList<>();
        for (PlanStepRecord step : ready) {
            Map<String, Object> input = resolveInput(task, plan, step);
            var constraintViolation = AgentConstraintPolicy.violation(
                    step.toolRef(), input, task.constraints(), task.projectId());
            if (constraintViolation.isPresent()) {
                failConstraint(task, run, null, constraintViolation.get());
                return false;
            }
            AgentPolicyEngine.PolicyDecision policy = policyEngine.evaluate(
                    step.toolRef(), step.riskLevel(), false, run.permissionSnapshot(), input);
            if (!policy.allowed() || policy.requiresConfirmation()) {
                failPolicy(task, run, step, policy);
                return false;
            }
            RuntimeDecision delegation = authorizeDelegation(task, run, null, step, input);
            if (!delegation.allowed() || delegation.requiresConfirmation()) {
                failDelegation(task, run, null, delegation);
                return false;
            }
            authorized.add(new PreparedBranch(step, input, delegation.autoExecuteWrite()));
        }

        RunRecord current = repository.findRun(run.id()).orElse(run);
        Map<String, Object> branchBudget = parallelBranchBudget(remainingBudget(current), authorized.size());
        String requestedJoin = text(task.constraints().get("parallelJoinPolicy")).toUpperCase();
        String joinPolicy = "PARTIAL_ALLOWED".equals(requestedJoin) ? requestedJoin : "ALL";
        long groupId = repository.createSubtaskGroup(
                run.id(), authorized.size(), branchBudget, joinPolicy);
        List<RunningBranch> branches = new ArrayList<>();
        for (PreparedBranch branch : authorized) {
            PlanStepRecord step = branch.step();
            int attempt = repository.nextAttempt(run.id(), step.id());
            if (attempt > run.budgetLimit().maxRetriesPerStep() + 1) {
                repository.completeSubtaskGroup(groupId, "FAILED", 0, branches.size());
                stopForInput(task, run, "RETRY_LIMIT", "并行分支重试次数已达到上限。");
                return false;
            }
            String idempotencyKey = sha256(task.id() + ":" + run.id() + ":" + step.id() + ":" + attempt);
            long stepRunId = repository.createStepRun(
                    run.id(), step.id(), attempt, idempotencyKey, redactInput(branch.input()));
            repository.addSubtaskGroupMember(groupId, stepRunId, step.stepKey(), branchBudget);
            events.publish(task.id(), "step.started", Map.of(
                    "stepRunId", stepRunId,
                    "stepKey", step.stepKey(),
                    "title", step.title(),
                    "attempt", attempt,
                    "subtaskGroupId", groupId
            ));
            if (!ensureBudgetReserved(task, run, step, stepRunId, attempt)) {
                repository.completeSubtaskGroup(groupId, "FAILED", 0, branches.size() + 1);
                return false;
            }
            branches.add(new RunningBranch(
                    step, stepRunId, attempt, idempotencyKey, branch.input(),
                    branch.delegatedWriteAuthorized(), branchBudget));
        }
        events.publish(task.id(), "subtask.group.started", Map.of(
                "subtaskGroupId", groupId,
                "maxParallelism", branches.size(),
                "joinPolicy", joinPolicy,
                "branchKeys", branches.stream().map(value -> value.step().stepKey()).toList()
        ));
        if (missionMetrics != null) missionMetrics.parallelism(branches.size());

        List<CompletableFuture<Boolean>> futures = branches.stream()
                .map(branch -> CompletableFuture.supplyAsync(
                        () -> executeParallelBranch(task, run, plan, branch), subtaskExecutor))
                .toList();
        int succeeded = 0;
        for (CompletableFuture<Boolean> future : futures) {
            if (Boolean.TRUE.equals(future.join())) succeeded++;
        }
        int failed = branches.size() - succeeded;
        String groupStatus = failed == 0 ? "COMPLETED"
                : "PARTIAL_ALLOWED".equals(joinPolicy) && succeeded > 0 ? "PARTIALLY_COMPLETED" : "FAILED";
        repository.completeSubtaskGroup(groupId, groupStatus, succeeded, failed);
        events.publish(task.id(), "subtask.group.completed", Map.of(
                "subtaskGroupId", groupId,
                "status", groupStatus,
                "succeededCount", succeeded,
                "failedCount", failed,
                "joinPolicy", joinPolicy
        ));
        if (failed > 0 && "ALL".equals(joinPolicy)) {
            stopForInput(task, repository.findRun(run.id()).orElse(run),
                    "PARALLEL_BRANCH_FAILED",
                    "并行只读检查有 " + failed + " 个分支失败；其他分支证据已保留。");
            return false;
        }
        if (failed > 0) repository.acceptFailedSubtaskMembers(groupId);
        if (completeRequestedPause(task.id(), run.id())) return false;
        return true;
    }

    private boolean executeParallelBranch(
            TaskRecord task,
            RunRecord run,
            PlanRecord plan,
            RunningBranch branch
    ) {
        try {
            return executeReadStep(
                    task, repository.findRun(run.id()).orElse(run), plan, branch.step(),
                    branch.stepRunId(), branch.attempt(), branch.idempotencyKey(), branch.input(),
                    null, branch.delegatedWriteAuthorized(), true, branch.budget());
        } catch (RuntimeException exc) {
            repository.finishStep(
                    branch.stepRunId(),
                    AgentStepStatus.FAILED,
                    Map.of("summary", "并行只读分支执行失败，其他分支不受影响。"),
                    null,
                    null,
                    classifyError(exc)
            );
            events.publish(task.id(), "step.failed", Map.of(
                    "stepRunId", branch.stepRunId(),
                    "errorCode", classifyError(exc),
                    "parallelBranch", true
            ));
            return false;
        }
    }

    private static Map<String, Object> parallelBranchBudget(
            Map<String, Object> remaining,
            int branchCount
    ) {
        int divisor = Math.max(1, branchCount);
        Map<String, Object> budget = new LinkedHashMap<>();
        budget.put("steps", 1);
        budget.put("toolCalls", 1);
        budget.put("modelTokens", Math.max(1, longValue(remaining.get("modelTokens")) / divisor));
        budget.put("costMicros", Math.max(1, longValue(remaining.get("costMicros")) / divisor));
        budget.put("wallTimeMillis", Math.max(1, longValue(remaining.get("wallTimeMillis")) / divisor));
        return budget;
    }

    private record PreparedBranch(
            PlanStepRecord step,
            Map<String, Object> input,
            boolean delegatedWriteAuthorized
    ) { }

    private record RunningBranch(
            PlanStepRecord step,
            long stepRunId,
            int attempt,
            String idempotencyKey,
            Map<String, Object> input,
            boolean delegatedWriteAuthorized,
            Map<String, Object> budget
    ) { }

    private boolean ensureBudgetReserved(TaskRecord task, RunRecord run, PlanStepRecord step, long stepRunId, int attempt) {
        if (repository.hasBudgetReservation(stepRunId)) return true;
        int toolCalls = "analysis.synthesize".equals(step.toolRef()) ? 0 : 1;
        if (repository.reserveBudget(run.id(), stepRunId, toolCalls, attempt > 1 ? 1 : 0).isPresent()) return true;
        repository.finishStep(stepRunId, AgentStepStatus.CANCELLED,
                Map.of("summary", "调用前预算原子预占失败，工具未执行。"), null, null, "BUDGET_RESERVATION_FAILED");
        RunRecord current = repository.findRun(run.id()).orElse(run);
        finishWithBudgetStop(task, current, List.of("atomicReservation"));
        return false;
    }

    private boolean executeReadStep(
            TaskRecord task,
            RunRecord run,
            PlanRecord plan,
            PlanStepRecord step,
            long stepRunId,
            int attempt,
            String idempotencyKey,
            Map<String, Object> input
    ) {
        return executeReadStep(
                task, run, plan, step, stepRunId, attempt, idempotencyKey, input,
                null, false, false, null);
    }

    private boolean executeReadStep(
            TaskRecord task,
            RunRecord run,
            PlanRecord plan,
            PlanStepRecord step,
            long stepRunId,
            int attempt,
            String idempotencyKey,
            Map<String, Object> input,
            boolean delegatedWriteAuthorized
    ) {
        return executeReadStep(
                task, run, plan, step, stepRunId, attempt, idempotencyKey, input, null,
                delegatedWriteAuthorized, false, null);
    }

    private boolean executeReadStep(
            TaskRecord task,
            RunRecord run,
            PlanRecord plan,
            PlanStepRecord step,
            long stepRunId,
            int attempt,
            String idempotencyKey,
            Map<String, Object> input,
            Map<String, Object> stagedResult,
            boolean delegatedWriteAuthorized
    ) {
        return executeReadStep(
                task, run, plan, step, stepRunId, attempt, idempotencyKey, input,
                stagedResult, delegatedWriteAuthorized, false, null);
    }

    private boolean executeReadStep(
            TaskRecord task,
            RunRecord run,
            PlanRecord plan,
            PlanStepRecord step,
            long stepRunId,
            int attempt,
            String idempotencyKey,
            Map<String, Object> input,
            Map<String, Object> stagedResult,
            boolean delegatedWriteAuthorized,
            boolean parallelBranch,
            Map<String, Object> branchBudget
    ) {
        input = withLatestInput(run.id(), input);
        RunRecord beforeCall = repository.findRun(run.id()).orElse(run);
        Map<String, Object> remaining = branchBudget == null
                ? remainingBudget(beforeCall)
                : new LinkedHashMap<>(branchBudget);
        // The same controller-owned Artifact snapshot is used both as synthesis input and
        // as the sensitivity authority. Upstream result text/flags never decide this boundary.
        List<Map<String, Object>> consumedArtifacts = repository.listArtifacts(task.id());
        AgentToolOutputSanitizer.ConsumedSensitivity consumedSensitivity =
                AgentToolOutputSanitizer.classifyConsumedArtifacts(consumedArtifacts);
        Map<String, Object> result;
        long elapsedMillis;
        boolean recoveringStagedResult = stagedResult != null && bool(stagedResult.get("_controllerStaged"));
        if (recoveringStagedResult) {
            result = new LinkedHashMap<>(stagedResult);
            elapsedMillis = Math.max(0, longValue(result.remove("_controllerElapsedMillis")));
            result.remove("_controllerStaged");
        } else {
            long started = System.nanoTime();
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("taskId", String.valueOf(task.id()));
            request.put("taskRunId", String.valueOf(run.id()));
            request.put("stepRunId", String.valueOf(stepRunId));
            request.put("userId", String.valueOf(task.userId()));
            request.put("projectId", task.projectId());
            request.put("conversationId", task.conversationId() == null ? null : String.valueOf(task.conversationId()));
            request.put("goal", task.goal());
            request.put("completionCriteria", task.completionCriteria());
            request.put("toolRef", step.toolRef());
            request.put("input", input);
            request.put("artifacts", consumedArtifacts);
            request.put("idempotencyKey", idempotencyKey);
            request.put("remainingBudget", remaining);
            request.put("delegatedWriteAuthorized", delegatedWriteAuthorized);
            if (delegatedWriteAuthorized) {
                RuntimeDecision writeDecision = authorizeDelegation(
                        task, run, stepRunId, step, input);
                if (!writeDecision.allowed() || !writeDecision.autoExecuteWrite()) {
                    failDelegation(task, run, stepRunId, writeDecision);
                    return false;
                }
                request.put("grantVersion", writeDecision.grantVersion());
                request.put("authorizationDecisionId", String.valueOf(writeDecision.authorizationDecisionId()));
            }
            try {
                long wallTimeMillis = Math.max(1, longValue(remaining.get("wallTimeMillis")));
                result = callWithLeaseHeartbeat(run.id(), () -> decisions.executeStep(
                        request, Duration.ofMillis(wallTimeMillis)));
            } catch (LeaseLostException exc) {
                throw exc;
            } catch (RuntimeException exc) {
                result = new LinkedHashMap<>();
                result.put("status", "ERROR");
                result.put("summary", "工具调用超时或连接中断，等待用户选择。");
                result.put("errorCode", "UPSTREAM_TIMEOUT");
                result.put("retryable", false);
                result.put("usage", Map.of("modelTokens", 0, "costMicros", 0));
            }
            elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        }
        result = AgentToolOutputSanitizer.sanitize(
                step.toolRef(), result, task.completionCriteria(), consumedSensitivity
        );
        String status = text(result.get("status")).toUpperCase();
        String summary = text(result.get("summary"));
        String fingerprint = fingerprint(step.toolRef(), input, summary);
        boolean repeated = repository.previousFingerprint(run.id(), step.toolRef()).map(fingerprint::equals).orElse(false);
        Map<String, Object> usage = map(result.get("usage"));
        long modelTokens = longValue(usage.get("modelTokens"));
        long costMicros = longValue(usage.get("costMicros"));
        boolean alreadySettled = repository.hasBudgetSettlement(stepRunId);
        if (!alreadySettled && (modelTokens > longValue(remaining.get("modelTokens"))
                || costMicros > longValue(remaining.get("costMicros"))
                || elapsedMillis > longValue(remaining.get("wallTimeMillis")))) {
            repository.finishStep(stepRunId, AgentStepStatus.FAILED,
                    Map.of("summary", parallelBranch
                            ? "并行分支超过其独立预算，结果未被采纳。"
                            : "工具返回的实际消耗超过调用前授予的剩余预算，结果未被采纳。"),
                    null, fingerprint,
                    parallelBranch ? "BRANCH_BUDGET_EXCEEDED" : "BUDGET_PROTOCOL_VIOLATION");
            if (!parallelBranch) {
                finishWithBudgetStop(
                        task, repository.findRun(run.id()).orElse(beforeCall), List.of("postCallUsage"));
            }
            return false;
        }
        if (!recoveringStagedResult) {
            Map<String, Object> staged = new LinkedHashMap<>(result);
            staged.put("_controllerStaged", true);
            staged.put("_controllerElapsedMillis", elapsedMillis);
            if (!repository.stageStepResult(stepRunId, staged)) throw new LeaseLostException();
        }
        int noProgressCount = repeated ? Math.max(2, beforeCall.noProgressCount() + 1) : 0;
        int completed = status.equals("SUCCESS") || status.equals("PARTIAL") ? 1 : 0;
        AgentBudgetUsage aggregate = repository.settleBudget(
                run.id(), stepRunId, completed, modelTokens, costMicros, elapsedMillis, noProgressCount
        ).orElse(null);
        if (aggregate == null) {
            repository.finishStep(stepRunId, AgentStepStatus.FAILED,
                    Map.of("summary", "调用后预算结算被硬上限拒绝，结果未被采纳。"),
                    null, fingerprint, "BUDGET_SETTLEMENT_REJECTED");
            finishWithBudgetStop(task, repository.findRun(run.id()).orElse(beforeCall), List.of("atomicSettlement"));
            return false;
        }
        String dataRef = nullableText(result.get("dataRef"));
        if (status.equals("ERROR") || summary.isBlank()) {
            String errorCode = text(result.get("errorCode"));
            if (errorCode.isBlank()) errorCode = "TOOL_ERROR";
            repository.finishStep(stepRunId, AgentStepStatus.FAILED, result, dataRef, fingerprint, errorCode);
            repository.createCheckpoint(run.id(), "STEP_FAILED", run.planVersion(), stepRunId, aggregate,
                    map(result.get("externalStateHashes")));
            events.publish(task.id(), "step.failed", Map.of("stepRunId", stepRunId, "errorCode", errorCode, "retryable", bool(result.get("retryable"))));
            if (parallelBranch) return false;
            if (bool(result.get("retryable")) && attempt <= run.budgetLimit().maxRetriesPerStep()) return true;
            if (!completeRequestedPause(task.id(), run.id())) {
                stopForInput(task, repository.findRun(run.id()).orElse(run), errorCode, summary.isBlank() ? "工具执行失败，需要用户处理。" : summary);
            }
            return false;
        }
        AgentStepStatus recordedStatus = status.equals("PARTIAL")
                ? (parallelBranch ? AgentStepStatus.FAILED : AgentStepStatus.WAITING_INPUT)
                : AgentStepStatus.SUCCEEDED;
        if (delegatedWriteAuthorized && status.equals("SUCCESS") && delegationGuard != null) {
            delegationGuard.captureCompensation(task, stepRunId, step.toolRef(), result);
        }
        repository.finishStep(stepRunId, recordedStatus, result, dataRef, fingerprint,
                status.equals("PARTIAL") ? "PARTIAL_RESULT" : null);
        repository.createCheckpoint(run.id(), "STEP_SUCCEEDED", run.planVersion(), stepRunId, aggregate,
                map(result.get("externalStateHashes")));
        List<Object> evidenceRefs = list(result.get("evidenceRefs"));
        Map<String, Object> artifactMetadata = new LinkedHashMap<>();
        artifactMetadata.put("contentTrust", text(result.get("contentTrust")));
        artifactMetadata.put("redacted", bool(result.get("redacted")));
        artifactMetadata.put("sensitivity", textOr(result.get("sensitivity"), "NONE"));
        artifactMetadata.put("toolRef", step.toolRef());
        artifactMetadata.put("criterionEvidence", list(result.get("criterionEvidence")));
        artifactMetadata.put("securityFlags", list(result.get("securityFlags")));
        repository.createArtifact(task.id(), run.id(), stepRunId, "EVIDENCE", step.title(), summary,
                dataRef, evidenceRefs, artifactMetadata);
        List<Object> securityFlags = list(result.get("securityFlags"));
        if (!securityFlags.isEmpty()) {
            events.publish(task.id(), "agent_security_event", Map.of(
                    "stepRunId", stepRunId,
                    "toolRef", step.toolRef(),
                    "flags", securityFlags,
                    "action", "UNTRUSTED_INSTRUCTIONS_IGNORED"
            ));
            if (delegationGuard != null) {
                delegationGuard.captureSecurityBoundary(
                        task, stepRunId, step.toolRef(), securityFlags);
            }
        }
        events.publish(task.id(), "step.completed", Map.of(
                "stepRunId", stepRunId,
                "stepKey", step.stepKey(),
                "summary", safeSummary(summary),
                "repeated", repeated
        ));
        events.publish(task.id(), "budget.updated", Map.of(
                "budgetUsed", aggregate.toMap(),
                "usageRatio", budgetGuard.usageRatio(run.budgetLimit(), aggregate)
        ));
        if (!parallelBranch && completeRequestedPause(task.id(), run.id())) return false;
        if (parallelBranch) return status.equals("SUCCESS");
        if (noProgressCount >= run.budgetLimit().maxConsecutiveNoProgress()) {
            stopForInput(task, repository.findRun(run.id()).orElse(run), "NO_PROGRESS", "连续调用没有获得新信息，执行已停止。");
            return false;
        }
        Map<String, Object> nextRequest = new LinkedHashMap<>();
        nextRequest.put("taskId", String.valueOf(task.id()));
        nextRequest.put("goal", task.goal());
        nextRequest.put("completionCriteria", task.completionCriteria());
        nextRequest.put("currentStepKey", step.stepKey());
        nextRequest.put("toolResult", result);
        nextRequest.put("remainingStepCount", Math.max(0, plan.steps().size() - aggregate.completedSteps()));
        nextRequest.put("budgetUsed", aggregate.toMap());
        nextRequest.put("budgetLimit", run.budgetLimit().toMap());
        nextRequest.put("latestUserInput", repository.latestInput(run.id()));
        Map<String, Object> next = decisions.nextAction(nextRequest);
        String action = text(next.get("action")).toUpperCase();
        if (action.equals("REQUEST_INPUT") || action.equals("STOP")) {
            stopForInput(task, repository.findRun(run.id()).orElse(run), action, text(next.get("reason")));
            return false;
        }
        if (action.equals("REVISE_PLAN")) {
            stopForInput(task, repository.findRun(run.id()).orElse(run), "PLAN_REVISION_SUGGESTED", text(next.get("reason")));
            return false;
        }
        if (action.equals("REQUEST_CONFIRMATION")) {
            stopForInput(task, repository.findRun(run.id()).orElse(run), "UNEXPECTED_CONFIRMATION", "只读步骤不能自行扩大为写操作；需要调整计划并重新审批。");
            return false;
        }
        return true;
    }

    private void createChangePreview(
            TaskRecord task,
            RunRecord run,
            PlanStepRecord step,
            long stepRunId,
            String idempotencyKey,
            Map<String, Object> input
    ) {
        repository.createCheckpoint(run.id(), "BEFORE_WRITE", run.planVersion(), stepRunId, run.budgetUsed());
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("taskId", String.valueOf(task.id()));
        request.put("taskRunId", String.valueOf(run.id()));
        request.put("stepRunId", String.valueOf(stepRunId));
        request.put("userId", String.valueOf(task.userId()));
        request.put("conversationId", task.conversationId() == null ? null : String.valueOf(task.conversationId()));
        request.put("goal", task.goal());
        request.put("toolRef", step.toolRef());
        request.put("input", input);
        request.put("idempotencyKey", idempotencyKey);
        Map<String, Object> preview = callWithLeaseHeartbeat(
                run.id(), () -> decisions.createChangePreview(request));
        long pendingOperationId = longValue(preview.get("pendingOperationId"));
        if (pendingOperationId <= 0) throw new IllegalStateException("Change Preview 未关联 Pending Operation。");
        long previewId = repository.createChangePreview(
                task.id(), run.id(), stepRunId, pendingOperationId,
                textOr(preview.get("resourceRef"), "agent-task://" + task.id()),
                textOr(preview.get("actionType"), step.toolRef()),
                map(preview.get("before")), map(preview.get("after")),
                textOr(preview.get("impact"), "确认后执行该写操作，并继续后续步骤。"),
                "WRITE_LOW", bool(preview.get("undoSupported")), map(preview.get("compensation")), idempotencyKey);
        Map<String, Object> output = new LinkedHashMap<>(preview);
        output.put("summary", textOr(preview.get("summary"), "写操作等待用户确认。"));
        repository.finishStep(stepRunId, AgentStepStatus.WAITING_CONFIRMATION, output,
                "pending-operation://" + pendingOperationId, null, null);
        repository.updateRunStatus(run.id(), "RUNNING", "AWAITING_CONFIRMATION");
        if (!repository.transitionTaskFromCurrent(task.id(), AgentTaskStatus.RUNNING, AgentTaskStatus.AWAITING_CONFIRMATION)) {
            throw new IllegalStateException("无法进入待确认状态。");
        }
        repository.createCheckpoint(run.id(), "AWAITING_CONFIRMATION", run.planVersion(), stepRunId, run.budgetUsed());
        events.publish(task.id(), "confirmation.required", Map.of(
                "previewId", previewId,
                "pendingOperationId", pendingOperationId,
                "stepRunId", stepRunId,
                "summary", safeSummary(text(output.get("summary")))
        ));
    }

    private void finalizeTask(TaskRecord task, RunRecord run, PlanRecord plan) {
        List<Map<String, Object>> stepRuns = repository.listStepRuns(run.id());
        List<Map<String, Object>> artifacts = repository.listArtifacts(task.id());
        Map<String, Object> review = decisions.reviewResult(Map.of(
                "taskId", String.valueOf(task.id()),
                "goal", task.goal(),
                "completionCriteria", task.completionCriteria(),
                "steps", stepRuns,
                "artifacts", artifacts,
                "budgetUsed", run.budgetUsed().toMap()
        ));
        String outcome = text(review.get("outcome")).toUpperCase();
        AgentTaskStatus finalStatus = switch (outcome) {
            case "COMPLETE" -> AgentTaskStatus.COMPLETED;
            case "FAILED" -> AgentTaskStatus.FAILED;
            default -> AgentTaskStatus.PARTIALLY_COMPLETED;
        };
        String summary = textOr(review.get("summary"), "任务执行结束。");
        repository.createArtifact(task.id(), run.id(), null, "RESULT", "最终结果", summary,
                nullableText(review.get("contentRef")), list(review.get("evidenceRefs")), review);
        repository.createCheckpoint(run.id(), "FINALIZED", run.planVersion(), null, run.budgetUsed());
        repository.updateRunStatus(run.id(), "RUNNING", finalStatus.name());
        if (!repository.transitionTaskFromCurrent(task.id(), AgentTaskStatus.RUNNING, finalStatus)) {
            throw new IllegalStateException("无法写入任务终态。");
        }
        events.publish(task.id(), "task.completed", Map.of(
                "status", finalStatus.name(),
                "summary", safeSummary(summary),
                "criteria", review.getOrDefault("criteria", List.of())
        ));
    }

    private void completePause(TaskRecord task, RunRecord run) {
        repository.updateRunStatus(run.id(), run.status(), "PAUSED");
        repository.transitionTaskFromCurrent(task.id(), AgentTaskStatus.PAUSING, AgentTaskStatus.PAUSED);
        repository.createCheckpoint(run.id(), "PAUSED", run.planVersion(), null, run.budgetUsed());
        events.publish(task.id(), "task.status.changed", Map.of("status", AgentTaskStatus.PAUSED.name()));
    }

    private boolean completeRequestedPause(long taskId, long runId) {
        TaskRecord currentTask = repository.findTask(taskId).orElse(null);
        if (currentTask == null || currentTask.status() != AgentTaskStatus.PAUSING) return false;
        RunRecord currentRun = repository.findRun(runId).orElse(null);
        if (currentRun == null) return false;
        completePause(currentTask, currentRun);
        return true;
    }

    private void pauseForFeatureFlag(TaskRecord task, RunRecord run) {
        if (!repository.transitionTaskFromCurrent(task.id(), AgentTaskStatus.RUNNING, AgentTaskStatus.PAUSING)) return;
        repository.updateRunStatus(run.id(), "RUNNING", "PAUSING");
        TaskRecord pausing = repository.findTask(task.id()).orElse(task);
        RunRecord current = repository.findRun(run.id()).orElse(run);
        completePause(pausing, current);
        events.publish(task.id(), "agent_dynamic_loop_disabled", Map.of(
                "reason", "FEATURE_FLAG", "v4Unaffected", true));
    }

    private void finishWithBudgetStop(TaskRecord task, RunRecord run, List<String> dimensions) {
        repository.createArtifact(task.id(), run.id(), null, "RESULT", "预算停止",
                "任务达到硬预算上限，已保留现有结果。", null, List.of(), Map.of("dimensions", dimensions));
        repository.createCheckpoint(run.id(), "BUDGET_STOP", run.planVersion(), null, run.budgetUsed());
        repository.updateRunStatus(run.id(), "RUNNING", "PARTIALLY_COMPLETED");
        repository.transitionTaskFromCurrent(task.id(), AgentTaskStatus.RUNNING, AgentTaskStatus.PARTIALLY_COMPLETED);
        events.publish(task.id(), "agent_budget_stopped", Map.of("dimensions", dimensions));
    }

    private void stopForInput(TaskRecord task, RunRecord run, String errorCode, String reason) {
        String safeReason = reason == null || reason.isBlank() ? "执行需要用户选择下一步。" : safeSummary(reason);
        repository.createCheckpoint(run.id(), "AWAITING_INPUT", run.planVersion(), null, run.budgetUsed());
        repository.updateRunStatus(run.id(), "RUNNING", "AWAITING_INPUT");
        repository.transitionTaskFromCurrent(task.id(), AgentTaskStatus.RUNNING, AgentTaskStatus.AWAITING_INPUT);
        events.publish(task.id(), "input.required", Map.of(
                "errorCode", errorCode == null ? "INPUT_REQUIRED" : errorCode,
                "reason", safeReason,
                "options", List.of("修复后继续", "跳过并部分完成", "调整计划", "终止")
        ));
    }

    private void failPolicy(TaskRecord task, RunRecord run, PlanStepRecord step, AgentPolicyEngine.PolicyDecision policy) {
        int attempt = repository.nextAttempt(run.id(), step.id());
        long stepRunId = repository.createStepRun(run.id(), step.id(), attempt,
                sha256(task.id() + ":policy:" + step.id() + ":" + attempt), Map.of());
        repository.finishStep(stepRunId, AgentStepStatus.FAILED, Map.of("summary", policy.reason()), null, null, policy.errorCode());
        repository.createCheckpoint(run.id(), "POLICY_REJECTED", run.planVersion(), stepRunId, run.budgetUsed());
        repository.updateRunStatus(run.id(), "RUNNING", "FAILED");
        repository.transitionTaskFromCurrent(task.id(), AgentTaskStatus.RUNNING, AgentTaskStatus.FAILED);
        events.publish(task.id(), "step.failed", Map.of("stepRunId", stepRunId, "errorCode", policy.errorCode(), "policyRejected", true));
    }

    private RuntimeDecision authorizeDelegation(
            TaskRecord task,
            RunRecord run,
            Long stepRunId,
            PlanStepRecord step,
            Map<String, Object> input
    ) {
        if (delegationGuard == null) return RuntimeDecision.interactive();
        return delegationGuard.authorize(
                task,
                run.id(),
                stepRunId,
                step.toolRef(),
                step.riskLevel(),
                step.writeIntent(),
                input
        );
    }

    private void failDelegation(
            TaskRecord task,
            RunRecord run,
            Long existingStepRunId,
            RuntimeDecision decision
    ) {
        long stepRunId = existingStepRunId == null ? 0 : existingStepRunId;
        if (existingStepRunId != null) {
            repository.finishStep(
                    stepRunId,
                    AgentStepStatus.FAILED,
                    Map.of("summary", "当前动作未满足持续委托边界。"),
                    null,
                    null,
                    decision.reasonCode()
            );
        }
        repository.createCheckpoint(
                run.id(), "DELEGATION_REJECTED", run.planVersion(), existingStepRunId, run.budgetUsed());
        repository.updateRunStatus(run.id(), "RUNNING", "AWAITING_INPUT");
        repository.transitionTaskFromCurrent(task.id(), AgentTaskStatus.RUNNING, AgentTaskStatus.AWAITING_INPUT);
        events.publish(task.id(), "authorization.denied", Map.of(
                "authorizationDecisionId", decision.authorizationDecisionId(),
                "reasonCode", decision.reasonCode(),
                "grantVersion", decision.grantVersion()
        ));
    }

    private void failConstraint(TaskRecord task, RunRecord run, Long existingStepRunId, String reason) {
        long stepRunId = existingStepRunId == null ? 0 : existingStepRunId;
        if (existingStepRunId != null) {
            repository.finishStep(stepRunId, AgentStepStatus.FAILED,
                    Map.of("summary", reason), null, null, "USER_CONSTRAINT_VIOLATION");
        }
        repository.createCheckpoint(run.id(), "CONSTRAINT_REJECTED", run.planVersion(), existingStepRunId, run.budgetUsed());
        repository.updateRunStatus(run.id(), "RUNNING", "FAILED");
        repository.transitionTaskFromCurrent(task.id(), AgentTaskStatus.RUNNING, AgentTaskStatus.FAILED);
        events.publish(task.id(), "step.failed", Map.of(
                "stepRunId", stepRunId,
                "errorCode", "USER_CONSTRAINT_VIOLATION",
                "policyRejected", true));
    }

    private void failTask(TaskRecord task, RunRecord run, String errorCode) {
        RunRecord current = repository.findRun(run.id()).orElse(run);
        if (!"RUNNING".equals(current.status())) return;
        repository.createCheckpoint(run.id(), "FAILED", run.planVersion(), null, current.budgetUsed());
        repository.updateRunStatus(run.id(), "RUNNING", "FAILED");
        repository.transitionTaskFromCurrent(task.id(), AgentTaskStatus.RUNNING, AgentTaskStatus.FAILED);
        events.publish(task.id(), "task.failed", Map.of("errorCode", errorCode));
    }

    private Map<String, Object> resolveInput(TaskRecord task, PlanRecord plan, PlanStepRecord step) {
        Map<String, Object> result = new LinkedHashMap<>(step.inputTemplate());
        result.replaceAll((key, value) -> {
            if (!(value instanceof String text)) return value;
            return text.replace("{{goal}}", task.goal())
                    .replace("{{taskId}}", String.valueOf(task.id()))
                    .replace("{{planVersion}}", String.valueOf(plan.version()));
        });
        return result;
    }

    private Map<String, Object> withLatestInput(long runId, Map<String, Object> input) {
        Map<String, Object> result = new LinkedHashMap<>(input);
        Map<String, Object> latest = repository.latestInput(runId);
        String body = text(latest.get("input"));
        String action = text(latest.get("action"));
        if (!body.isBlank()) result.put("latestUserInput", body);
        if (!action.isBlank()) result.put("latestUserAction", action);
        return result;
    }

    private boolean validateExternalState(TaskRecord task, RunRecord run) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("taskId", String.valueOf(task.id()));
        request.put("userId", String.valueOf(task.userId()));
        request.put("permissionSnapshot", run.permissionSnapshot());
        request.put("expectedExternalState", repository.latestExternalState(run.id()));
        Map<String, Object> check = callWithLeaseHeartbeat(
                run.id(), () -> decisions.resumeCheck(request));
        if (bool(check.get("valid")) && !bool(check.get("changed"))) {
            events.publish(task.id(), "resume.validated", Map.of(
                    "runId", run.id(),
                    "reason", textOr(check.get("reason"), "环境状态与检查点一致。")
            ));
            return true;
        }
        String reason = textOr(check.get("reason"), "恢复前环境校验失败，需要用户确认后续路径。");
        repository.finishStep(repository.findRunningStep(run.id()).map(StepRunRecord::id).orElse(0L),
                AgentStepStatus.FAILED, Map.of("summary", reason), null, null, "RESUME_STATE_CHANGED");
        stopForInput(task, run, "RESUME_STATE_CHANGED", reason);
        events.publish(task.id(), "resume.validation.required", Map.of(
                "runId", run.id(),
                "changed", bool(check.get("changed")),
                "reason", safeSummary(reason)
        ));
        return false;
    }

    private <T> T callWithLeaseHeartbeat(long runId, Callable<T> callable) {
        AtomicBoolean leaseLost = new AtomicBoolean(false);
        if (!repository.heartbeat(runId, workerId, LEASE_SECONDS)) throw new LeaseLostException();
        ScheduledFuture<?> heartbeat = leaseScheduler.scheduleAtFixedRate(() -> {
            try {
                if (!repository.heartbeat(runId, workerId, LEASE_SECONDS)) leaseLost.set(true);
            } catch (RuntimeException ignored) {
                leaseLost.set(true);
            }
        }, LEASE_SECONDS / 3L, LEASE_SECONDS / 3L, TimeUnit.SECONDS);
        try {
            T result = callable.call();
            if (leaseLost.get()) throw new LeaseLostException();
            return result;
        } catch (LeaseLostException exc) {
            throw exc;
        } catch (RuntimeException exc) {
            throw exc;
        } catch (Exception exc) {
            throw new IllegalStateException("外部调用失败。", exc);
        } finally {
            heartbeat.cancel(false);
        }
    }

    private static final class LeaseLostException extends RuntimeException {
    }

    private Map<String, Object> remainingBudget(RunRecord run) {
        return Map.of(
                "steps", Math.max(0, run.budgetLimit().maxSteps() - run.budgetUsed().completedSteps()),
                "toolCalls", Math.max(0, run.budgetLimit().maxToolCalls() - run.budgetUsed().toolCalls()),
                "modelTokens", Math.max(0, run.budgetLimit().maxModelTokens() - run.budgetUsed().modelTokens()),
                "costMicros", Math.max(0, run.budgetLimit().maxCostMicros() - run.budgetUsed().costMicros()),
                "wallTimeMillis", Math.max(0, run.budgetLimit().maxWallTimeMillis() - run.budgetUsed().elapsedMillis())
        );
    }

    private Map<String, Object> redactInput(Map<String, Object> input) {
        Map<String, Object> result = new LinkedHashMap<>();
        input.forEach((key, value) -> {
            if (key.toLowerCase().contains("secret") || key.toLowerCase().contains("token")) {
                result.put(key, "[REDACTED]");
            } else {
                result.put(key, value);
            }
        });
        return result;
    }

    private String fingerprint(String toolRef, Map<String, Object> input, String summary) {
        try {
            return sha256(toolRef + "|" + objectMapper.writeValueAsString(input) + "|" + summary);
        } catch (JsonProcessingException exc) {
            return sha256(toolRef + "|" + summary);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exc) {
            throw new IllegalStateException("SHA-256 不可用。", exc);
        }
    }

    private static String classifyError(RuntimeException exc) {
        String name = exc.getClass().getSimpleName().toUpperCase();
        if (name.contains("TIMEOUT")) return "UPSTREAM_TIMEOUT";
        return "CONTROL_LOOP_ERROR";
    }

    private static String safeSummary(String value) {
        String compact = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return compact.length() > 500 ? compact.substring(0, 500) + "…" : compact;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> source ? new LinkedHashMap<>((Map<String, Object>) source) : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return value instanceof List<?> source ? new ArrayList<>((List<Object>) source) : new ArrayList<>();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String textOr(Object value, String fallback) {
        String result = text(value);
        return result.isBlank() ? fallback : result;
    }

    private static String nullableText(Object value) {
        String result = text(value);
        return result.isBlank() ? null : result;
    }

    private static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean bool && bool;
    }
}
