package com.example.aiapp.service.impl;

import com.example.aiapp.domain.dto.ChatRequestDTO;
import com.example.aiapp.config.JacksonConfig;
import com.example.aiapp.domain.entity.ChatMessageEntity;
import com.example.aiapp.infrastructure.client.AiServiceClient;
import com.example.aiapp.mapper.ChatMessageMapper;
import com.example.aiapp.mapper.ConversationMapper;
import com.example.aiapp.service.AiConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatStreamHttpTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void actualChunkedHttpKeepsProgressAndOptionalReasoningThroughCompletionHistoryAndReplay(boolean hasReasoning)
            throws Exception {
        ObjectMapper json = productionJson();
        assertThat(json.writeValueAsString(Map.of("id", 9_007_199_254_740_993L)))
                .contains("\"id\":\"9007199254740993\"");
        CountDownLatch liveReceived = new CountDownLatch(hasReasoning ? 2 : 1);
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        AtomicInteger upstreamCalls = new AtomicInteger();
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/ai/chat/stream", exchange -> {
            try {
                upstreamCalls.incrementAndGet();
                var request = json.readTree(exchange.getRequestBody());
                assertThat(request.path("turnId").asText()).isEqualTo("turn-http");
                assertThat(request.path("stream").asBoolean()).isTrue();
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
                exchange.sendResponseHeaders(200, 0);
                // Python _sse emits UTF-8 JSON with spaces and LF-delimited frames.
                String live = ": keep-alive\n\n"
                        + "event: agent.step\ndata: {\"turnId\": \"turn-http\", \"sequence\": 1, \"step\": \"tool_agent\", \"status\": \"running\", \"summary\": \"正在查询天气\"}\n\n"
                        + (hasReasoning ? "event: reasoning.delta\ndata: {\"turnId\": \"turn-http\", \"sequence\": 1, \"step\": \"writer_agent\", \"kind\": \"reasoning\", \"delta\": \"先核对天气数据🙂。\"}\n\n" : "");
                // Force every UTF-8 boundary across writes, including Chinese and emoji.
                for (byte value : live.getBytes(StandardCharsets.UTF_8)) {
                    exchange.getResponseBody().write(value);
                    exchange.getResponseBody().flush();
                }
                assertThat(liveReceived.await(5, TimeUnit.SECONDS)).as("events arrive before HTTP stream completes").isTrue();
                String tail = "event: agent.step\ndata: {\"turnId\": \"turn-http\", \"sequence\": 2, \"step\": \"tool_agent\", \"status\": \"completed\", \"summary\": \"天气查询完成\", \"durationMs\": 100.0}\n\n"
                        + "event: delta\ndata: {\"text\": \"今天晴天。\"}\n\n"
                        + "event: meta\ndata: {\"agentTrace\": [{\"agent\": \"tool_agent\", \"toolCount\": 1}], \"toolResults\": [{\"tool\": \"weather\", \"ok\": true, \"content\": \"已查询天气\"}]}\n\n"
                        + "event: done\ndata: {\"turnId\": \"turn-http\"}\n\n";
                exchange.getResponseBody().write(tail.getBytes(StandardCharsets.UTF_8));
            } catch (Throwable failure) {
                serverFailure.set(failure);
            } finally {
                exchange.close();
            }
        });
        upstream.start();
        try {
            AiServiceClient client = new AiServiceClient(WebClient.builder()
                    .baseUrl("http://127.0.0.1:" + upstream.getAddress().getPort()).build());
            AiConfigService config = mock(AiConfigService.class);
            when(config.currentConfigMap()).thenReturn(Map.of());
            ChatMessageMapper messages = mock(ChatMessageMapper.class);
            List<ChatMessageEntity> snapshots = new ArrayList<>();
            doAnswer(invocation -> {
                ChatMessageEntity source = invocation.getArgument(0);
                ChatMessageEntity snapshot = new ChatMessageEntity();
                snapshot.setRole(source.getRole());
                snapshot.setConversationId(source.getConversationId());
                snapshot.setTurnId(source.getTurnId());
                snapshot.setContent(source.getContent());
                snapshot.setMetadataJson(source.getMetadataJson());
                snapshots.add(snapshot);
                return 1;
            }).when(messages).updateById(any(ChatMessageEntity.class));
            ChatServiceImpl service = new ChatServiceImpl(client, config, messages, mock(ConversationMapper.class), json);
            List<String> frames = new ArrayList<>();
            ChatRequestDTO request = new ChatRequestDTO("temporary-conversation", "turn-http", null, "今天天气如何", List.of());
            service.stream(42L, request, frame -> {
                frames.add(frame);
                if (frame.startsWith("event: reasoning.delta")
                        || (frame.startsWith("event: agent.step") && frame.contains("\"status\":\"running\""))) {
                    liveReceived.countDown();
                }
            });
            assertThat(serverFailure.get()).isNull();
            ChatMessageEntity saved = snapshots.get(snapshots.size() - 1);
            assertThat(saved.getContent()).isEqualTo("今天晴天。");
            assertThat(saved.getMetadataJson()).contains("agentProgress", "天气查询完成", "COMPLETED");
            assertThat(saved.getMetadataJson()).contains("\"sequence\":\"2\"");
            assertThat(String.join("", frames)).contains("event: agent.step", "天气查询完成", "event: done");
            when(messages.selectByConversationId("temporary-conversation")).thenReturn(List.of(saved));
            var history = service.listMessages(42L, "temporary-conversation").get(0).metadata();
            assertThat(history.get("agentProgress").toString()).contains("tool_agent", "completed", "天气查询完成");
            assertThat(history).containsKeys("processStartedAt", "processCompletedAt");
            if (hasReasoning) {
                assertThat(history.get("reasoning").toString()).contains("先核对天气数据🙂。", "sequence=1");
            } else {
                assertThat(history).doesNotContainKey("reasoning");
            }
            when(messages.selectByConversationIdAndTurnId("temporary-conversation", "turn-http")).thenReturn(saved);
            List<String> liveFrames = List.copyOf(frames);
            frames.clear();
            service.stream(42L, request, frames::add);
            assertThat(String.join("", frames)).contains("event: meta", "agentProgress", "天气查询完成");
            if (hasReasoning) assertThat(String.join("", frames)).contains("先核对天气数据🙂。");
            assertThat(upstreamCalls).hasValue(1);
            exportFixture(json, hasReasoning, liveFrames, saved, history, frames);
        } finally {
            upstream.stop(0);
        }
    }

    @Test
    void cancellingPersistedStringSequencesPreservesCompletedStepsAndAdvancesFromLatestSequence() throws Exception {
        ObjectMapper json = productionJson();
        ChatMessageEntity saved = new ChatMessageEntity();
        saved.setRole("assistant");
        saved.setConversationId("temporary-conversation");
        saved.setTurnId("turn-http");
        saved.setContent("部分回答");
        saved.setMetadataJson(json.writeValueAsString(Map.of(
                "turnId", "turn-http", "phase", "RESPONDING", "processStartedAt", 1789662595003L,
                "reasoning", Map.of("kind", "reasoning", "text", "正在检查。", "sequence", 5L),
                "agentProgress", List.of(
                        Map.of("turnId", "turn-http", "step", "tool_agent", "status", "completed", "sequence", 7L),
                        Map.of("turnId", "turn-http", "step", "writer_agent", "status", "running", "sequence", 8L)
                ))));
        ChatMessageMapper messages = mock(ChatMessageMapper.class);
        when(messages.selectByConversationIdAndTurnId("temporary-conversation", "turn-http")).thenReturn(saved);
        ChatServiceImpl service = new ChatServiceImpl(mock(AiServiceClient.class), mock(AiConfigService.class),
                messages, mock(ConversationMapper.class), json);
        var cancelled = service.cancelTurn(42L, "temporary-conversation", "turn-http");
        assertThat(cancelled.metadata().get("agentProgress").toString())
                .contains("sequence=7", "status=completed", "sequence=9", "status=failed");
        assertThat(cancelled.metadata().get("reasoning").toString()).contains("sequence=5", "正在检查。");
        assertThat(cancelled.metadata()).containsEntry("processStartedAt", 1789662595003L).containsKey("processCompletedAt");
    }

    private ObjectMapper productionJson() {
        try (var context = new AnnotationConfigApplicationContext(JacksonConfig.class, JacksonAutoConfiguration.class)) {
            return context.getBean(ObjectMapper.class);
        }
    }

    private void exportFixture(ObjectMapper json, boolean hasReasoning, List<String> liveFrames,
            ChatMessageEntity saved, Map<String, Object> history, List<String> replayFrames) throws Exception {
        String filename = System.getProperty("lyle.processFixture");
        if (filename == null || filename.isBlank()) return;
        Path path = Path.of(filename).toAbsolutePath();
        Files.createDirectories(path.getParent());
        var fixture = Files.exists(path) ? (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(path.toFile())
                : json.createObjectNode();
        fixture.set(hasReasoning ? "withReasoning" : "withoutReasoning", json.valueToTree(Map.of(
                "turnId", "turn-http", "liveFrames", liveFrames,
                "storedMetadata", json.readTree(saved.getMetadataJson()),
                "historyMetadata", json.readTree(json.writeValueAsString(history)), "replayFrames", replayFrames)));
        json.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), fixture);
    }
}
