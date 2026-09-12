package com.example.aiapp.infrastructure.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AiServiceClientRagScopeTest {
    @Test
    void postsTrustedScopeAsJsonInsteadOfGlobalQueryParameters() throws Exception {
        var captured = new AtomicReference<MockClientHttpRequest>();
        WebClient webClient = WebClient.builder().baseUrl("http://python-service")
                .defaultHeader("X-Internal-Token", "test-internal-only")
                .exchangeFunction(request -> {
                    assertThat(request.method()).isEqualTo(HttpMethod.POST);
                    assertThat(request.url().getPath()).isEqualTo("/ai/rag/chunks");
                    assertThat(request.url().getQuery()).isNull();
                    assertThat(request.headers().getFirst("X-Internal-Token")).isEqualTo("test-internal-only");
                    var outgoing = new MockClientHttpRequest(request.method(), request.url());
                    captured.set(outgoing);
                    return request.writeTo(outgoing, ExchangeStrategies.withDefaults()).then(Mono.just(
                            ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                                    .body("{\"items\":[]}").build()));
                }).build();
        var payload = Map.<String, Object>of("userId", "7", "knowledgeBaseIds", List.of("10"), "limit", 50,
                "knowledgeScope", Map.of("userId", "7", "documentRevisions", Map.of("10", Map.of("1", "new"))));
        assertThat(new AiServiceClient(webClient).getRagChunksScoped(payload)).isEqualTo(Map.of("items", List.of()));
        ObjectMapper mapper = new ObjectMapper();
        assertThat(mapper.readTree(captured.get().getBodyAsString().block())).isEqualTo(mapper.valueToTree(payload));
    }
}
