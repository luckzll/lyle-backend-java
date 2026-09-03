package com.example.aiapp.controller;

import com.example.aiapp.config.V63FeatureProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 运行时配置接口。
 */
@RestController
@RequestMapping("/runtime-config")
public class RuntimeConfigController {
    private final V63FeatureProperties features;

    public RuntimeConfigController(V63FeatureProperties features) {
        this.features = features;
    }

    @GetMapping
    Map<String, Boolean> runtimeConfig() {
        return Map.of(
                "authLoginV63Enabled", features.authLogin(),
                "chatWorkspaceDropEnabled", features.workspaceDrop(),
                "chatFolderAttachmentsEnabled", features.folderAttachments()
        );
    }
}
