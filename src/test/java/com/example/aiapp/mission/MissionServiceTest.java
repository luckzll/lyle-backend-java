package com.example.aiapp.mission;

import com.example.aiapp.agenttask.application.AgentTaskService;
import com.example.aiapp.mission.application.DelegationPolicyEngine;
import com.example.aiapp.mission.application.MissionScheduleCalculator;
import com.example.aiapp.mission.application.MissionService;
import com.example.aiapp.mission.config.MissionFeatureFlags;
import com.example.aiapp.mission.infrastructure.MissionRepository;
import com.example.aiapp.mission.infrastructure.MissionRepository.BudgetUsageRecord;
import com.example.aiapp.mission.infrastructure.MissionRepository.MissionRecord;
import com.example.aiapp.mission.infrastructure.python.MissionAiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MissionServiceTest {

    @Test
    void detailReturnsCurrentPeriodUsageFromAuthoritativeRunLedger() {
        MissionRepository repository = mock(MissionRepository.class);
        MissionRecord mission = mission();
        when(repository.findMission(10L, 7L)).thenReturn(Optional.of(mission));
        when(repository.listGrants(10L)).thenReturn(List.of());
        when(repository.listTriggers(10L)).thenReturn(List.of());
        when(repository.listOccurrences(10L, 20)).thenReturn(List.of());
        when(repository.listRuns(10L, 20)).thenReturn(List.of());
        when(repository.listAuthorizationDecisions(10L, 30)).thenReturn(List.of());
        when(repository.aggregateMissionUsage(eq(10L), any(), any()))
                .thenReturn(new BudgetUsageRecord(2, 2, 0, 0, 2029));

        MissionFeatureFlags flags = new MissionFeatureFlags(
                true,
                false,
                false,
                true,
                "7",
                "project-health,research-weekly,release-readiness",
                "todo.propose,file.patch.propose"
        );
        MissionService service = new MissionService(
                repository,
                mock(MissionAiClient.class),
                new DelegationPolicyEngine(flags),
                new MissionScheduleCalculator(),
                flags,
                mock(AgentTaskService.class),
                new ObjectMapper()
        );

        Map<String, Object> detail = service.detail(7L, 10L);

        assertThat(detail.get("periodUsage")).isEqualTo(Map.of(
                "completedSteps", 2L,
                "toolCalls", 2L,
                "modelTokens", 0L,
                "costMicros", 0L,
                "wallTimeMillis", 2029L,
                "periodStart", "2026-07-13T00:00Z",
                "periodEnd", "2026-07-20T00:00Z"
        ));
    }

    private static MissionRecord mission() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 17, 1, 0);
        return new MissionRecord(
                10L,
                7L,
                9L,
                "项目健康守护",
                "检查项目健康",
                List.of("保留证据"),
                Map.of("workspaceId", 47),
                "project-health",
                "ACTIVE",
                1,
                Map.of(),
                Map.of(),
                Map.of("maxToolCalls", 12),
                Map.of(
                        "maxToolCalls", 60,
                        "maxModelTokens", 400_000,
                        "maxCostMicros", 10_000_000,
                        "maxWallTimeMillis", 4_500_000
                ),
                "WEEKLY",
                now.minusDays(1),
                now.plusDays(20),
                now.plusHours(1),
                3L,
                now.minusDays(1),
                now,
                null
        );
    }
}
