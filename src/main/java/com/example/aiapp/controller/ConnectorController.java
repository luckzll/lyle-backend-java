package com.example.aiapp.controller;

import com.example.aiapp.infrastructure.client.AiServiceClient;
import com.example.aiapp.utils.CurrentUserUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/connectors")
public class ConnectorController {

    private final AiServiceClient aiServiceClient;

    public ConnectorController(AiServiceClient aiServiceClient) {
        this.aiServiceClient = aiServiceClient;
    }

    @GetMapping
    Map<String, Object> list() {
        return aiServiceClient.listConnectors();
    }

    @GetMapping("/{connectorName}/status")
    Map<String, Object> status(@PathVariable String connectorName) {
        return aiServiceClient.getConnectorStatus(connectorName);
    }

    @GetMapping("/{connectorName}/list")
    Map<String, Object> resources(
            @PathVariable String connectorName,
            @RequestParam(required = false) String repo,
            @RequestParam(required = false) String state,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return aiServiceClient.listConnectorResources(connectorName, repo, state, limit);
    }

    @GetMapping("/{connectorName}/search")
    Map<String, Object> search(
            @PathVariable String connectorName,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(required = false) String repo,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return aiServiceClient.searchConnector(connectorName, q, repo, limit);
    }

    @GetMapping("/{connectorName}/resources/{resourceId}")
    Map<String, Object> resource(@PathVariable String connectorName, @PathVariable String resourceId) {
        return aiServiceClient.getConnectorResource(connectorName, resourceId);
    }

    @PostMapping("/{connectorName}/preview-create")
    Map<String, Object> previewCreate(@PathVariable String connectorName, @RequestBody Map<String, Object> request) {
        return aiServiceClient.previewConnectorCreate(currentUserId(), connectorName, request);
    }

    private String currentUserId() {
        return String.valueOf(CurrentUserUtils.currentUserId());
    }
}
