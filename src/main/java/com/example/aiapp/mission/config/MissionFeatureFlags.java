package com.example.aiapp.mission.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class MissionFeatureFlags {

    private final boolean enabled;
    private final boolean executionEnabled;
    private final boolean r1Enabled;
    private final boolean shadowOnly;
    private final Set<String> enabledUsers;
    private final Set<String> enabledTemplates;
    private final Set<String> enabledR1Tools;

    public MissionFeatureFlags(
            @Value("${app.mission.enabled:false}") boolean enabled,
            @Value("${app.mission.execution-enabled:false}") boolean executionEnabled,
            @Value("${app.mission.r1-enabled:false}") boolean r1Enabled,
            @Value("${app.mission.shadow-only:false}") boolean shadowOnly,
            @Value("${app.mission.enabled-users:}") String enabledUsers,
            @Value("${app.mission.enabled-templates:project-health,research-weekly,release-readiness}") String enabledTemplates,
            @Value("${app.mission.enabled-r1-tools:todo.propose,file.patch.propose}") String enabledR1Tools
    ) {
        this.enabled = enabled;
        this.executionEnabled = executionEnabled;
        this.r1Enabled = r1Enabled;
        this.shadowOnly = shadowOnly;
        this.enabledUsers = values(enabledUsers);
        this.enabledTemplates = values(enabledTemplates);
        this.enabledR1Tools = values(enabledR1Tools);
    }

    public boolean allowsControlPlane(long userId) {
        return enabled && contains(enabledUsers, String.valueOf(userId));
    }

    public boolean allowsTemplate(long userId, String templateId) {
        return allowsControlPlane(userId) && contains(enabledTemplates, templateId);
    }

    public boolean allowsExecution(long userId, String templateId) {
        return allowsTemplate(userId, templateId) && executionEnabled && !shadowOnly;
    }

    public boolean allowsR1(long userId, String toolRef) {
        return allowsControlPlane(userId)
                && executionEnabled
                && r1Enabled
                && !shadowOnly
                && contains(enabledR1Tools, toolRef);
    }

    public boolean shadowOnly() {
        return shadowOnly || !executionEnabled;
    }

    private static boolean contains(Set<String> values, String value) {
        return values.contains("*") || values.contains(value);
    }

    private static Set<String> values(String source) {
        return Arrays.stream(source.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }
}
