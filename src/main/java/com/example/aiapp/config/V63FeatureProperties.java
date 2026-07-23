package com.example.aiapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.v63")
public record V63FeatureProperties(
        Boolean authLoginEnabled,
        Boolean workspaceDropEnabled,
        Boolean folderAttachmentsEnabled
) {
    public boolean authLogin() {
        return authLoginEnabled == null || authLoginEnabled;
    }

    public boolean workspaceDrop() {
        return workspaceDropEnabled == null || workspaceDropEnabled;
    }

    public boolean folderAttachments() {
        return folderAttachmentsEnabled == null || folderAttachmentsEnabled;
    }
}
