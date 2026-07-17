package com.example.aiapp.mission.infrastructure.metrics;

import com.example.aiapp.mission.infrastructure.MissionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
public class MissionMetrics {

    private final MeterRegistry registry;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();
    private final DistributionSummary periodBudgetRatio;
    private final DistributionSummary subtaskParallelism;

    public MissionMetrics(MeterRegistry registry, MissionRepository repository) {
        this.registry = registry;
        Gauge.builder("mission_active_total", repository, MissionRepository::countActiveMissions)
                .description("Current number of active Missions")
                .register(registry);
        this.periodBudgetRatio = DistributionSummary.builder("mission_period_budget_ratio")
                .description("Observed Mission period budget utilization ratio")
                .baseUnit("ratio")
                .maximumExpectedValue(1.0)
                .register(registry);
        this.subtaskParallelism = DistributionSummary.builder("subtask_parallelism")
                .description("Observed number of concurrently dispatched R0 branches")
                .baseUnit("branches")
                .maximumExpectedValue(3.0)
                .register(registry);
    }

    public void occurrence(String type, String status) {
        counter(
                "mission_trigger_occurrence_total",
                "type", label(type),
                "status", label(status)
        ).increment();
    }

    public void run(String template, String status) {
        counter(
                "mission_run_total",
                "template", label(template),
                "status", label(status)
        ).increment();
    }

    public void runDuration(String template, String status, Duration duration) {
        timer(
                "mission_run_duration_seconds",
                "template", label(template),
                "status", label(status)
        ).record(Math.max(0, duration.toMillis()), TimeUnit.MILLISECONDS);
    }

    public void authorization(String decision, String risk, String tool) {
        counter(
                "delegation_decision_total",
                "decision", label(decision),
                "risk", label(risk),
                "tool", label(tool)
        ).increment();
    }

    public void denied(String reason) {
        counter("delegation_denied_total", "reason", label(reason)).increment();
    }

    public void periodBudgetRatio(double value) {
        periodBudgetRatio.record(Math.max(0.0, Math.min(1.0, value)));
    }

    public void exception(String type, String severity) {
        counter(
                "mission_exception_total",
                "type", label(type),
                "severity", label(severity)
        ).increment();
    }

    public void compensation(String status, String tool) {
        counter(
                "compensation_total",
                "status", label(status),
                "tool", label(tool)
        ).increment();
    }

    public void misfire(String policy) {
        counter("trigger_misfire_total", "policy", label(policy)).increment();
    }

    public void parallelism(int count) {
        subtaskParallelism.record(Math.max(0, Math.min(3, count)));
    }

    private Counter counter(String name, String... tags) {
        String key = name + "|" + String.join("|", tags);
        return counters.computeIfAbsent(key, ignored -> registry.counter(name, tags));
    }

    private Timer timer(String name, String... tags) {
        String key = name + "|" + String.join("|", tags);
        return timers.computeIfAbsent(key, ignored -> Timer.builder(name).tags(tags).register(registry));
    }

    private static String label(String value) {
        if (value == null || value.isBlank()) return "unknown";
        String compact = value.trim().toLowerCase().replaceAll("[^a-z0-9_.-]+", "_");
        return compact.length() > 80 ? compact.substring(0, 80) : compact;
    }
}
