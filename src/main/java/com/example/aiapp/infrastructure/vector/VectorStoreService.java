package com.example.aiapp.infrastructure.vector;

import com.example.aiapp.config.QdrantProperties;
import org.springframework.util.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Component
public class VectorStoreService {

    private final QdrantProperties properties;
    private final WebClient qdrantWebClient;

    public VectorStoreService(QdrantProperties properties) {
        this.properties = properties;
        WebClient.Builder builder = WebClient.builder().baseUrl(properties.endpoint());
        if (StringUtils.hasText(properties.apiKey())) {
            builder.defaultHeader("api-key", properties.apiKey());
        }
        this.qdrantWebClient = builder.build();
    }

    public void upsert(String collectionName, String id, List<Float> vector, String payload) {
        if (!properties.enabled()) {
            return;
        }

        Map<String, Object> body = Map.of(
                "points", List.of(Map.of(
                        "id", id,
                        "vector", vector,
                        "payload", Map.of("text", payload)
                ))
        );
        qdrantWebClient.put()
                .uri("/collections/{collection}/points?wait=true", collectionName)
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .block();
    }

    public List<VectorSearchResult> search(String collectionName, List<Float> vector, int topK) {
        if (!properties.enabled()) {
            return List.of();
        }
        // Java 后端保留 Qdrant 管理能力；AI 检索主链路在 Python 服务中执行。
        return List.of();
    }
}
