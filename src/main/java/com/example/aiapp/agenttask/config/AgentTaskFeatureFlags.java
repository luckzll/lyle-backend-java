package com.example.aiapp.agenttask.config;

import com.example.aiapp.agenttask.infrastructure.repository.AgentTaskRepository.PlanRecord;
import com.example.aiapp.agenttask.infrastructure.repository.AgentTaskRepository.TaskRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class AgentTaskFeatureFlags {

    private final boolean dynamicEnabled;
    private final Set<String> enabledTemplates;
    private final Set<String> enabledTools;
    private final Set<String> enabledUsers;
    private final Set<String> enabledModels;

    public AgentTaskFeatureFlags(
            @Value("${app.agent-task.dynamic-enabled:false}") boolean dynamicEnabled,
            @Value("${app.agent-task.enabled-templates:project-inspection,release-check,research}") String enabledTemplates,
            @Value("${app.agent-task.enabled-tools:project.inspect,workspace.git_status,workspace.docs_check,release.check,research.search,memory.snapshot,analysis.synthesize,todo.propose,file.patch.propose}") String enabledTools,
            @Value("${app.agent-task.enabled-users:}") String enabledUsers,
            @Value("${app.agent-task.enabled-models:default}") String enabledModels
    ) {
        this.dynamicEnabled = dynamicEnabled;
        this.enabledTemplates = values(enabledTemplates);
        this.enabledTools = values(enabledTools);
        this.enabledUsers = values(enabledUsers);
        this.enabledModels = values(enabledModels);
    }

    public boolean allowsCreation(long userId, String templateId) {
        return dynamicEnabled
                && contains(enabledUsers, String.valueOf(userId))
                && templateId != null
                && contains(enabledTemplates, templateId);
    }

    public boolean allows(TaskRecord task, PlanRecord plan, Map<String, Object> permissionSnapshot) {
        if (!allowsCreation(task.userId(), task.templateId())) return false;
        if (plan == null || plan.steps().stream().anyMatch(step -> !contains(enabledTools, step.toolRef()))) return false;
        String model = permissionSnapshot == null ? "default" : String.valueOf(permissionSnapshot.getOrDefault("model", "default"));
        if (model.isBlank()) model = "default";
        return contains(enabledModels, model);
    }

    private static boolean contains(Set<String> values, String value) {
        return values.contains("*") || values.contains(value);
    }

    private static Set<String> values(String source) {
        return Arrays.stream(source.split(","))
                .map(String::trim).filter(value -> !value.isBlank()).collect(Collectors.toUnmodifiableSet());
    }
}
