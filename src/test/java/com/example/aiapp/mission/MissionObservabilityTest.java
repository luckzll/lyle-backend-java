package com.example.aiapp.mission;

import com.example.aiapp.mission.infrastructure.MissionRepository;
import com.example.aiapp.mission.infrastructure.events.MissionEventPublisher;
import com.example.aiapp.mission.infrastructure.metrics.MissionMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MissionObservabilityTest {

    @Test
    void missionEventSequenceIsAllocatedWhileHoldingTheMissionRowLock() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(contains("FOR UPDATE"), eq(Long.class), eq(10L)))
                .thenReturn(17L);
        when(jdbc.update(
                contains("SET event_sequence=?"),
                eq(18L),
                eq(10L),
                eq(17L)
        )).thenReturn(1);
        when(jdbc.update(
                contains("INSERT INTO mission_event"),
                anyLong(),
                eq(10L),
                eq(18L),
                eq("mission.updated"),
                eq("ACTIVE"),
                any(),
                any()
        )).thenReturn(1);
        MissionRepository repository = new MissionRepository(jdbc, new ObjectMapper());

        long sequence = repository.appendMissionEvent(
                10L,
                "mission.updated",
                "ACTIVE",
                Map.of("status", "ACTIVE")
        );

        assertThat(sequence).isEqualTo(18L);
        verify(jdbc).queryForObject(contains("FOR UPDATE"), eq(Long.class), eq(10L));
        verify(jdbc).update(
                contains("SET event_sequence=?"),
                eq(18L),
                eq(10L),
                eq(17L)
        );
    }

    @Test
    void missionSsePersistsRedactedPayloadAndReplaysAfterLastSequence() {
        MissionRepository repository = mock(MissionRepository.class);
        when(repository.appendMissionEvent(eq(10L), eq("authorization.decided"), eq("DENY"), any()))
                .thenReturn(42L);
        when(repository.listMissionEvents(10L, 41L, 1000)).thenReturn(List.of());
        MissionEventPublisher publisher = new MissionEventPublisher(repository);

        long sequence = publisher.publish(10L, "authorization.decided", Map.of(
                "status", "DENY",
                "summary", "  blocked\nby policy  ",
                "authorizationToken", "must-not-leak",
                "nested", Map.of("password", "must-not-leak")
        ));
        publisher.subscribe(10L, 41L).complete();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(repository).appendMissionEvent(
                eq(10L), eq("authorization.decided"), eq("DENY"), payload.capture());
        verify(repository).listMissionEvents(10L, 41L, 1000);
        assertThat(sequence).isEqualTo(42L);
        assertThat(payload.getValue().get("authorizationToken")).isEqualTo("[REDACTED]");
        assertThat(payload.getValue().get("summary")).isEqualTo("blocked by policy");
        assertThat(payload.getValue().get("nested"))
                .isEqualTo(Map.of("password", "[REDACTED]"));
    }

    @Test
    void normalSseDisconnectRemovesSubscriberWithoutSecondCompletionDispatch() {
        MissionRepository repository = mock(MissionRepository.class);
        when(repository.appendMissionEvent(eq(10L), eq("mission.updated"), eq("ACTIVE"), any()))
                .thenReturn(43L);
        MissionEventPublisher publisher = new MissionEventPublisher(repository);
        DisconnectingEmitter emitter = new DisconnectingEmitter();
        @SuppressWarnings("unchecked")
        Map<Long, CopyOnWriteArrayList<SseEmitter>> subscribers =
                (Map<Long, CopyOnWriteArrayList<SseEmitter>>) ReflectionTestUtils.getField(publisher, "emitters");
        subscribers.put(10L, new CopyOnWriteArrayList<>(List.of(emitter)));

        publisher.publish(10L, "mission.updated", Map.of("status", "ACTIVE"));

        assertThat(subscribers).doesNotContainKey(10L);
        assertThat(emitter.completed).isFalse();
    }

    @Test
    void allElevenV6MetricFamiliesAreRegisteredAndRecordable() {
        MissionRepository repository = mock(MissionRepository.class);
        when(repository.countActiveMissions()).thenReturn(2L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MissionMetrics metrics = new MissionMetrics(registry, repository);

        metrics.occurrence("FIXED_TIME", "PENDING");
        metrics.run("release-readiness", "COMPLETED");
        metrics.runDuration("release-readiness", "COMPLETED", Duration.ofSeconds(2));
        metrics.authorization("DENY", "R2", "git.commit");
        metrics.denied("RISK_TOO_HIGH");
        metrics.periodBudgetRatio(0.5);
        metrics.exception("AUTHORIZATION", "HIGH");
        metrics.compensation("SUCCEEDED", "file.patch.propose");
        metrics.misfire("SKIP");
        metrics.parallelism(3);

        List<String> names = List.of(
                "mission_active_total",
                "mission_trigger_occurrence_total",
                "mission_run_total",
                "mission_run_duration_seconds",
                "delegation_decision_total",
                "delegation_denied_total",
                "mission_period_budget_ratio",
                "mission_exception_total",
                "compensation_total",
                "trigger_misfire_total",
                "subtask_parallelism"
        );
        assertThat(names).allSatisfy(name ->
                assertThat(registry.find(name).meter()).as(name).isNotNull());
    }

    private static final class DisconnectingEmitter extends SseEmitter {
        private boolean completed;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            throw new IOException("Broken pipe");
        }

        @Override
        public synchronized void complete() {
            completed = true;
            super.complete();
        }
    }
}
