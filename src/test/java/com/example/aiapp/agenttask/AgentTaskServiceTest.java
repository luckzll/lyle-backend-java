package com.example.aiapp.agenttask;

import com.example.aiapp.agenttask.application.AgentTaskService;
import com.example.aiapp.agenttask.config.AgentTaskFeatureFlags;
import com.example.aiapp.agenttask.domain.AgentBudget;
import com.example.aiapp.agenttask.domain.AgentBudgetUsage;
import com.example.aiapp.agenttask.domain.AgentStepStatus;
import com.example.aiapp.agenttask.domain.AgentTaskStatus;
import com.example.aiapp.agenttask.domain.state.AgentTaskStateMachine;
import com.example.aiapp.agenttask.infrastructure.events.AgentEventPublisher;
import com.example.aiapp.agenttask.infrastructure.python.AgentDecisionClient;
import com.example.aiapp.agenttask.infrastructure.repository.AgentTaskRepository;
import com.example.aiapp.agenttask.infrastructure.repository.AgentTaskRepository.ChangePreviewRecord;
import com.example.aiapp.agenttask.infrastructure.repository.AgentTaskRepository.RunRecord;
import com.example.aiapp.agenttask.infrastructure.repository.AgentTaskRepository.TaskRecord;
import com.example.aiapp.agenttask.infrastructure.worker.AgentRunWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentTaskServiceTest {

    private AgentTaskRepository repository;
    private AgentDecisionClient decisions;
    private AgentEventPublisher events;
    private AgentRunWorker worker;
    private AgentTaskService service;

    @BeforeEach
    void setUp() {
        repository = mock(AgentTaskRepository.class);
        decisions = mock(AgentDecisionClient.class);
        events = mock(AgentEventPublisher.class);
        worker = mock(AgentRunWorker.class);
        AgentTaskFeatureFlags flags = new AgentTaskFeatureFlags(true, "*", "*", "*", "*");
        service = new AgentTaskService(repository, new AgentTaskStateMachine(), decisions, events, worker, flags);
        when(repository.findIdempotentTask(anyLong(), anyString(), anyString())).thenReturn(Optional.empty());
        when(repository.reserveIdempotency(anyLong(), anyString(), anyString(), anyLong())).thenReturn(true);
        when(repository.listArtifacts(anyLong())).thenReturn(List.of());
        when(repository.listStepRuns(anyLong())).thenReturn(List.of());
        when(repository.findPendingPreview(anyLong())).thenReturn(Optional.empty());
    }

    @Test
    void confirmedTransportStatusWithFailedToolResultNeverSucceedsStep() {
        TaskRecord waiting = task(AgentTaskStatus.AWAITING_CONFIRMATION, 4);
        TaskRecord awaitingInput = task(AgentTaskStatus.AWAITING_INPUT, 5);
        RunRecord run = run("AWAITING_CONFIRMATION");
        ChangePreviewRecord preview = new ChangePreviewRecord(
                21, 1, 11, 31, 41L, "project-task://new", "todo.create",
                Map.of(), Map.of("title", "任务"), "创建任务", "WRITE_LOW", false,
                Map.of(), "PENDING", "key", LocalDateTime.now(), null);
        when(repository.findTask(1, 7)).thenReturn(Optional.of(waiting), Optional.of(awaitingInput), Optional.of(awaitingInput));
        when(repository.findRun(11)).thenReturn(Optional.of(run));
        when(repository.findPendingPreview(11)).thenReturn(Optional.of(preview), Optional.empty());
        when(decisions.operationStatus(7, 41)).thenReturn(Map.of(
                "status", "CONFIRMED", "result", Map.of("ok", false, "message", "数据库写入失败")));
        when(repository.updateRunStatus(11, "AWAITING_CONFIRMATION", "AWAITING_INPUT")).thenReturn(true);
        when(repository.transitionTaskFromCurrent(1, AgentTaskStatus.AWAITING_CONFIRMATION, AgentTaskStatus.AWAITING_INPUT)).thenReturn(true);

        Map<String, Object> result = service.resume(7, 1, 4, "resume-key");

        assertThat(result.get("status")).isEqualTo("AWAITING_INPUT");
        verify(repository).finishStep(eq(31L), eq(AgentStepStatus.FAILED), argThat(value -> Boolean.FALSE.equals(value.get("ok"))),
                eq("pending-operation://41"), isNull(), eq("WRITE_EXECUTION_FAILED"));
        verify(repository, never()).finishStep(eq(31L), eq(AgentStepStatus.SUCCEEDED), anyMap(), any(), any(), any());
    }

    @Test
    void userInputBodyAndChosenActionArePersistedBeforeResume() {
        TaskRecord waiting = task(AgentTaskStatus.AWAITING_INPUT, 8);
        TaskRecord running = task(AgentTaskStatus.RUNNING, 9);
        when(repository.findTask(1, 7)).thenReturn(Optional.of(waiting), Optional.of(running));
        when(repository.findRun(11)).thenReturn(Optional.of(run("RUNNING")));
        when(repository.transitionTask(1, 8, AgentTaskStatus.AWAITING_INPUT, AgentTaskStatus.RUNNING)).thenReturn(true);
        when(repository.updateRunStatus(11, "AWAITING_INPUT", "RUNNING")).thenReturn(true);

        Map<String, Object> result = service.provideInput(
                7, 1, 8, Map.of("input", "GitHub 已重新连接，请继续", "action", "RETRY"), "input-key");

        assertThat(result.get("status")).isEqualTo("RUNNING");
        verify(repository).saveInput(1, 11, "RETRY", "GitHub 已重新连接，请继续");
        verify(repository).retryLatestWaitingStep(11);
        verify(worker).enqueue(11);
    }

    private static TaskRecord task(AgentTaskStatus status, long version) {
        LocalDateTime now = LocalDateTime.now();
        return new TaskRecord(1, 7, null, null, "任务", "检查项目", List.of("有证据"), Map.of(), null,
                status, "READ", 1, null, 11L, version, now, now);
    }

    private static RunRecord run(String status) {
        LocalDateTime now = LocalDateTime.now();
        return new RunRecord(11, 1, 1, status, "USER", Map.of(), AgentBudget.DEFAULT,
                AgentBudgetUsage.zero(), 0, null, null, null, now, null, null, 0, now, now);
    }
}
