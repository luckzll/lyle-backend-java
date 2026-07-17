package com.example.aiapp.mission.application;

import com.example.aiapp.mission.config.MissionFeatureFlags;
import com.example.aiapp.mission.infrastructure.MissionRepository;
import com.example.aiapp.mission.infrastructure.MissionRepository.TriggerRecord;
import com.example.aiapp.mission.infrastructure.MissionRepository.OccurrenceCompletionRecord;
import com.example.aiapp.mission.infrastructure.events.MissionEventPublisher;
import com.example.aiapp.mission.infrastructure.metrics.MissionMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

@Component
public class MissionScheduler {

    private static final Logger log = LoggerFactory.getLogger(MissionScheduler.class);

    private final MissionRepository repository;
    private final MissionService missionService;
    private final MissionScheduleCalculator schedules;
    private final MissionFeatureFlags featureFlags;
    private final MissionEventPublisher events;
    private final MissionMetrics metrics;
    private final String workerId = "mission-scheduler-" + UUID.randomUUID();

    public MissionScheduler(
            MissionRepository repository,
            MissionService missionService,
            MissionScheduleCalculator schedules,
            MissionFeatureFlags featureFlags,
            MissionEventPublisher events,
            MissionMetrics metrics
    ) {
        this.repository = repository;
        this.missionService = missionService;
        this.schedules = schedules;
        this.featureFlags = featureFlags;
        this.events = events;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${app.mission.scheduler-scan-millis:15000}")
    public void scan() {
        for (TriggerRecord trigger : repository.findDueTriggersWithoutValidGrant(50)) {
            skipExpiredGrant(trigger);
        }
        repository.expireDueMissions();
        var completions = repository.findTerminalTaskOccurrences();
        repository.completeTerminalTaskOccurrences();
        completions.forEach(this::completed);
        if (featureFlags.shadowOnly()) return;
        for (TriggerRecord trigger : repository.findDueTriggers(50)) {
            process(trigger);
        }
    }

    private void skipExpiredGrant(TriggerRecord trigger) {
        LocalDateTime scheduledFor = trigger.nextFireAt();
        if (scheduledFor == null) return;
        try {
            missionService.createSkippedScheduledOccurrence(
                    trigger, scheduledFor, "GRANT_EXPIRED");
            LocalDateTime next = schedules.nextFire(
                    trigger.type(), trigger.config(), trigger.timezone(), scheduledFor);
            repository.updateTriggerNextFire(trigger.id(), trigger.version(), next);
            missionService.refreshMissionNextRun(trigger.missionId());
        } catch (RuntimeException exc) {
            log.warn(
                    "Expired grant trigger skip failed. missionId={} triggerId={} scheduledFor={}",
                    trigger.missionId(), trigger.id(), scheduledFor, exc
            );
        }
    }

    private void process(TriggerRecord trigger) {
        LocalDateTime scheduledFor = trigger.nextFireAt();
        if (scheduledFor == null) return;
        try {
            long occurrenceId = missionService.createScheduledOccurrence(trigger, scheduledFor);
            LocalDateTime next = schedules.nextFire(
                    trigger.type(), trigger.config(), trigger.timezone(), scheduledFor);
            if (!repository.updateTriggerNextFire(trigger.id(), trigger.version(), next)) return;
            missionService.refreshMissionNextRun(trigger.missionId());

            boolean missed = Duration.between(scheduledFor, LocalDateTime.now(ZoneOffset.UTC)).toMinutes() >= 5;
            if (missed && "SKIP".equals(trigger.misfirePolicy())) {
                repository.markOccurrenceSkipped(occurrenceId);
                metrics.misfire(trigger.misfirePolicy());
                metrics.occurrence(trigger.type(), "SKIPPED");
                events.publish(trigger.missionId(), "mission.triggered", Map.of(
                        "status", "SKIPPED",
                        "occurrenceId", String.valueOf(occurrenceId),
                        "triggerType", trigger.type(),
                        "reasonCode", "MISFIRE_POLICY_SKIP",
                        "summary", "错过触发窗口，已按 SKIP 策略跳过。"
                ));
                return;
            }
            missionService.executeOccurrence(occurrenceId, workerId);
        } catch (RuntimeException exc) {
            log.warn(
                    "Mission trigger processing failed. missionId={} triggerId={} scheduledFor={}",
                    trigger.missionId(), trigger.id(), scheduledFor, exc
            );
        }
    }

    private void completed(OccurrenceCompletionRecord value) {
        metrics.run(value.templateId(), value.status());
        metrics.runDuration(
                value.templateId(),
                value.status(),
                Duration.between(value.createdAt(), value.updatedAt())
        );
        metrics.occurrence("EXECUTION", "COMPLETED");
        events.publish(value.missionId(), "mission.run.completed", Map.of(
                "status", value.status(),
                "occurrenceId", String.valueOf(value.occurrenceId()),
                "agentTaskId", String.valueOf(value.agentTaskId()),
                "summary", "持续任务运行已结束。"
        ));
    }
}
