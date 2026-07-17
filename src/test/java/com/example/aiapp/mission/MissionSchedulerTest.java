package com.example.aiapp.mission.application;

import com.example.aiapp.mission.config.MissionFeatureFlags;
import com.example.aiapp.mission.infrastructure.MissionRepository;
import com.example.aiapp.mission.infrastructure.MissionRepository.TriggerRecord;
import com.example.aiapp.mission.infrastructure.events.MissionEventPublisher;
import com.example.aiapp.mission.infrastructure.metrics.MissionMetrics;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MissionSchedulerTest {

    @Test
    void expiredGrantTriggerIsRecordedAsSkippedAndNeverExecutedOrRenewed() {
        MissionRepository repository = mock(MissionRepository.class);
        MissionService service = mock(MissionService.class);
        LocalDateTime due = LocalDateTime.of(2026, 7, 17, 1, 0);
        TriggerRecord trigger = new TriggerRecord(
                30, 10, "FIXED_TIME",
                Map.of("frequency", "DAILY", "time", "09:00"),
                "Asia/Shanghai", "RUN_ONCE", true, due, 4,
                due.minusDays(1), due.minusDays(1)
        );
        when(repository.findDueTriggersWithoutValidGrant(50)).thenReturn(List.of(trigger));
        when(repository.findTerminalTaskOccurrences()).thenReturn(List.of());
        when(repository.findDueTriggers(50)).thenReturn(List.of());
        when(service.createSkippedScheduledOccurrence(trigger, due, "GRANT_EXPIRED"))
                .thenReturn(88L);
        when(repository.updateTriggerNextFire(eq(30L), eq(4L), any())).thenReturn(true);

        MissionFeatureFlags flags = new MissionFeatureFlags(
                true, true, false, false, "7",
                "project-health,research-weekly,release-readiness",
                "todo.propose,file.patch.propose"
        );
        MissionScheduler scheduler = new MissionScheduler(
                repository,
                service,
                new MissionScheduleCalculator(),
                flags,
                mock(MissionEventPublisher.class),
                mock(MissionMetrics.class)
        );

        scheduler.scan();

        verify(service).createSkippedScheduledOccurrence(trigger, due, "GRANT_EXPIRED");
        verify(repository).expireDueMissions();
        verify(service, never()).executeOccurrence(anyLong(), any(String.class));
        verify(repository, never()).approveGrant(anyLong(), anyInt(), anyLong());
    }
}
