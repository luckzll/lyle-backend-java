package com.example.aiapp.service.impl;

import com.example.aiapp.config.JacksonConfig;
import com.example.aiapp.domain.dto.ChatRequestDTO;
import com.example.aiapp.domain.entity.ChatMessageEntity;
import com.example.aiapp.infrastructure.client.AiServiceClient;
import com.example.aiapp.mapper.ChatMessageMapper;
import com.example.aiapp.mapper.ConversationMapper;
import com.example.aiapp.service.AiConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChatStreamToolCallsTest {
    private static final ObjectMapper JSON = productionJson();

    @Test
    void realHttpAndProductionJacksonPreserveInterleavedCallsAcrossLiveHistoryAndReplay() throws Exception {
        CountDownLatch liveReceived = new CountDownLatch(1);
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/ai/chat/stream", exchange -> {
            try {
                assertThat(JSON.readTree(exchange.getRequestBody()).path("turnId").asText()).isEqualTo("turn-tools");
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                exchange.sendResponseHeaders(200, 0);
                String start = step(100, "running") + reasoning(1, "先检查需求🙂。") + event("tool.call", call("first", 1, "running"));
                for (byte value : start.getBytes(StandardCharsets.UTF_8)) {
                    exchange.getResponseBody().write(value);
                    exchange.getResponseBody().flush();
                }
                assertThat(liveReceived.await(5, TimeUnit.SECONDS)).isTrue();
                String tail = reasoning(2, "确认查询结果。") + event("tool.call", call("first", 2, "completed"))
                        + event("tool.call", call("second", 3, "running")) + reasoning(3, "准备等待确认。")
                        + event("tool.call", call("second", 4, "awaiting_confirmation")) + reasoning(4, "将保留操作。")
                        + step(101, "completed") + event("delta", Map.of("text", "已查询，请确认后续操作。"))
                        + event("meta", Map.of("toolCalls", List.of(call("forged", 99, "completed")),
                                "processTimeline", List.of(Map.of("type", "tool", "id", "forged"))))
                        + event("done", Map.of());
                exchange.getResponseBody().write(tail.getBytes(StandardCharsets.UTF_8));
            } catch (Throwable error) {
                serverFailure.set(error);
            } finally {
                exchange.close();
            }
        });
        upstream.start();
        try {
            Fixture fixture = new Fixture(new AiServiceClient(WebClient.builder()
                    .baseUrl("http://127.0.0.1:" + upstream.getAddress().getPort()).build()));
            fixture.run(frame -> {
                if (frame.startsWith("event: tool.call") && frame.contains("\"status\":\"running\"")) liveReceived.countDown();
            });
            assertThat(serverFailure.get()).isNull();
            ChatMessageEntity saved = fixture.latest();
            assertThat(saved.getContent()).isEqualTo("已查询，请确认后续操作。");
            assertThat(saved.getMetadataJson()).contains("\"sequence\":\"4\"").doesNotContain("forged");
            Map<String, Object> history = fixture.history(saved);
            List<Map<String, Object>> calls = items(history, "toolCalls");
            assertThat(calls).extracting(call -> call.get("callId")).containsExactly("first", "second");
            assertThat(calls).extracting(call -> call.get("status")).containsExactly("completed", "awaiting_confirmation");
            assertThat(items(history, "processTimeline")).extracting(item -> item.get("id"))
                    .containsExactly("tool_agent", "r-1", "first", "r-2", "second", "r-3");
            assertThat(items(history, "processTimeline").get(5))
                    .containsEntry("text", "准备等待确认。将保留操作。").containsEntry("sequence", 4L);
            assertThat(history.get("reasoning").toString()).contains("先检查需求🙂。确认查询结果。准备等待确认。将保留操作。");
            List<String> liveFrames = List.copyOf(fixture.frames);
            assertThat(String.join("", liveFrames)).contains("event: tool.call", "\"sequence\":\"1\"").doesNotContain("forged");
            when(fixture.messages.selectByConversationIdAndTurnId("temporary-conversation", "turn-tools")).thenReturn(saved);
            fixture.frames.clear();
            fixture.run(frame -> {});
            assertThat(String.join("", fixture.frames)).contains("processTimeline", "toolCalls", "first", "second", "r-3")
                    .doesNotContain("event: tool.call", "event: reasoning.delta");
            exportFixture(liveFrames, saved, history, fixture.frames);
        } finally {
            upstream.stop(0);
        }
    }

    @Test
    void rejectsWrongTurnMalformedDuplicateRegressingAndRawToolPayloads() throws Exception {
        Fixture fixture = new Fixture();
        List<String> frames = new ArrayList<>();
        Map<String, Object> first = new LinkedHashMap<>(call("one", 2, "running"));
        first.put("rawArguments", Map.of("password", "PRIVATE"));
        first.put("result", "PRIVATE");
        first.put("exception", "PRIVATE");
        first.put("summary", "Authorization: Bearer sensitive-credential");
        frames.add(event("tool.call", first));
        frames.add(event("tool.call", call("one", 1, "failed")));
        frames.add(event("tool.call", call("one", 2, "failed")));
        frames.add(event("tool.call", call("one", 3, "completed")));
        frames.add(event("tool.call", call("one", 4, "running")));
        frames.add(event("tool.call", call("one", 5, "failed")));
        for (Map.Entry<String, Object> invalid : Map.<String, Object>of(
                "turnId", "other", "callId", "bad/id", "tool", "bad tool", "status", "unknown",
                "sequence", "1e3", "title", Map.of("private", "PRIVATE")).entrySet()) {
            Map<String, Object> changed = new LinkedHashMap<>(call("invalid", 10, "running"));
            changed.put(invalid.getKey(), invalid.getValue());
            frames.add(event("tool.call", changed));
        }
        frames.add(event("done", Map.of("toolCalls", List.of(call("forged", 99, "running")))));
        when(fixture.client.streamChat(any())).thenReturn(Flux.fromIterable(frames));
        fixture.run(frame -> {});
        assertThat(String.join("", fixture.frames)).doesNotContain("PRIVATE", "sensitive-credential", "other", "forged", "invalid", "rawArguments", "exception");
        assertThat(fixture.frames.stream().filter(frame -> frame.startsWith("event: tool.call"))).hasSize(2);
        assertThat(items(fixture.history(fixture.latest()), "toolCalls")).singleElement()
                .satisfies(call -> assertThat(call).containsEntry("callId", "one").containsEntry("status", "completed").containsEntry("sequence", 3L));
    }

    @Test
    void keepsSameToolInvocationsDistinctAndPrunesOldReferencesAtTheSnapshotLimit() {
        Fixture fixture = new Fixture();
        List<String> frames = new ArrayList<>();
        for (int index = 1; index <= 130; index++) frames.add(event("tool.call", call("call-" + index, index, "completed")));
        frames.add(event("tool.call", call("call-3", 200, "completed")));
        frames.add(event("done", Map.of()));
        when(fixture.client.streamChat(any())).thenReturn(Flux.fromIterable(frames));
        fixture.run(frame -> {});
        Map<String, Object> history = fixture.history(fixture.latest());
        assertThat(items(history, "toolCalls")).hasSize(128);
        assertThat(items(history, "toolCalls").get(0)).containsEntry("callId", "call-3").containsEntry("sequence", 200L);
        assertThat(items(history, "processTimeline")).hasSize(128);
        assertThat(items(history, "processTimeline").get(0)).containsEntry("id", "call-3");
    }

    @Test
    void everyExitClosesOnlyUnfinishedToolsWithoutInventingSuccessfulResults() {
        for (String terminal : List.of("done", "error", "failed-status", "disconnect")) {
            Fixture fixture = new Fixture();
            List<String> frames = new ArrayList<>(List.of(event("tool.call", call("finished", 1, "completed")),
                    event("tool.call", call("running", 2, "running")), event("tool.call", call("waiting", 3, "needs_clarification"))));
            if (terminal.equals("done")) frames.add(event("done", Map.of()));
            if (terminal.equals("error")) frames.add(event("error", Map.of("message", "失败")));
            if (terminal.equals("failed-status")) {
                frames.add(event("turn.status", Map.of("phase", "FAILED", "message", "失败")));
                frames.add(event("done", Map.of()));
            }
            when(fixture.client.streamChat(any())).thenReturn(Flux.fromIterable(frames));
            if (terminal.equals("disconnect") || terminal.equals("error")) assertThatThrownBy(() -> fixture.run(frame -> {})).isInstanceOf(IllegalStateException.class);
            else fixture.run(frame -> {});
            List<Map<String, Object>> calls = items(fixture.history(fixture.latest()), "toolCalls");
            assertThat(calls).extracting(call -> call.get("status")).containsExactly("completed", "interrupted", "needs_clarification");
            assertThat(calls.get(1)).containsEntry("sequence", 4L);
            assertThat(String.join("", fixture.frames)).contains("\"status\":\"interrupted\"");
        }
    }

    @Test
    void cancellationRestoresStringSequencesAndRetryClearsOldCallsAndTimeline() throws Exception {
        Fixture fixture = new Fixture();
        ChatMessageEntity saved = new ChatMessageEntity();
        saved.setRole("assistant");
        saved.setConversationId("temporary-conversation");
        saved.setTurnId("turn-tools");
        saved.setMetadataJson(JSON.writeValueAsString(Map.of("turnId", "turn-tools", "phase", "RESPONDING",
                "toolCalls", List.of(withStringSequence(call("one", 7, "completed")), withStringSequence(call("two", 8, "running"))),
                "processTimeline", List.of(Map.of("type", "tool", "id", "one"), Map.of("type", "tool", "id", "two")))));
        when(fixture.messages.selectByConversationIdAndTurnId("temporary-conversation", "turn-tools")).thenReturn(saved);
        var cancelled = fixture.service.cancelTurn(42L, "temporary-conversation", "turn-tools");
        assertThat(items(cancelled.metadata(), "toolCalls")).extracting(call -> call.get("status")).containsExactly("completed", "interrupted");
        assertThat(items(cancelled.metadata(), "toolCalls").get(1)).containsEntry("sequence", 9L);
        assertThat(items(cancelled.metadata(), "processTimeline")).hasSize(2);
        ChatMessageEntity user = new ChatMessageEntity();
        user.setContent("再次查询");
        when(fixture.messages.selectUserByConversationIdAndTurnId("temporary-conversation", "turn-tools")).thenReturn(user);
        when(fixture.client.streamChat(any())).thenReturn(Flux.just(event("delta", Map.of("text", "重试成功")), event("done", Map.of())));
        fixture.service.stream(42L, new ChatRequestDTO("temporary-conversation", "turn-tools", null, "再次查询", List.of(), List.of(), true), fixture.frames::add);
        assertThat(fixture.history(fixture.latest())).doesNotContainKeys("toolCalls", "processTimeline");
    }

    @Test
    void lateCancellationPreservesCompletedAnswerAndProcessMetadata() {
        Fixture fixture = new Fixture();
        when(fixture.client.streamChat(any())).thenReturn(Flux.just(
                reasoning(1, "已核对天气。"), event("tool.call", call("one", 1, "completed")),
                event("delta", Map.of("text", "天气晴朗。")), event("done", Map.of())));
        fixture.run(frame -> {});
        ChatMessageEntity saved = fixture.latest();
        String metadata = saved.getMetadataJson();
        Map<String, Object> expectedHistory = fixture.history(saved);
        when(fixture.messages.selectByConversationIdAndTurnId("temporary-conversation", "turn-tools")).thenReturn(saved);
        clearInvocations(fixture.messages);

        var response = fixture.service.cancelTurn(42L, "temporary-conversation", "turn-tools");

        assertThat(response.content()).isEqualTo("天气晴朗。");
        assertThat(response.metadata()).isEqualTo(expectedHistory).containsEntry("phase", "COMPLETED");
        assertThat(saved.getMetadataJson()).isEqualTo(metadata);
        verify(fixture.messages, never()).updateById(any(ChatMessageEntity.class));
    }

    @Test
    void repeatedCancellationPreservesTheFirstFailedSnapshot() throws Exception {
        Fixture fixture = new Fixture();
        ChatMessageEntity saved = new ChatMessageEntity();
        saved.setRole("assistant");
        saved.setConversationId("temporary-conversation");
        saved.setTurnId("turn-tools");
        saved.setContent("已生成的部分回答。");
        saved.setMetadataJson(JSON.writeValueAsString(Map.of("turnId", "turn-tools", "phase", "RESPONDING",
                "toolCalls", List.of(withStringSequence(call("one", 7, "running"))),
                "processTimeline", List.of(Map.of("type", "tool", "id", "one")))));
        when(fixture.messages.selectByConversationIdAndTurnId("temporary-conversation", "turn-tools")).thenReturn(saved);
        var first = fixture.service.cancelTurn(42L, "temporary-conversation", "turn-tools");
        String metadata = saved.getMetadataJson();
        clearInvocations(fixture.messages);

        var repeated = fixture.service.cancelTurn(42L, "temporary-conversation", "turn-tools");

        assertThat(repeated.content()).isEqualTo(first.content());
        assertThat(repeated.metadata()).isEqualTo(first.metadata()).containsEntry("phase", "FAILED");
        assertThat(saved.getMetadataJson()).isEqualTo(metadata);
        verify(fixture.messages, never()).updateById(any(ChatMessageEntity.class));
    }

    @Test
    void terminalCancellationWhileWorkerIsStillActivePreservesSnapshotAndDoesNotInterruptIt() {
        for (String phase : List.of("COMPLETED", "FAILED")) {
            Fixture fixture = new Fixture();
            when(fixture.client.streamChat(any())).thenReturn(Flux.just(
                    reasoning(1, "已核对天气。"), event("tool.call", call("one", 1, "completed")),
                    event("delta", Map.of("text", "已返回的回答。")),
                    event("turn.status", Map.of("phase", phase, "message", "原始结束说明")), event("done", Map.of())));
            List<String> checkedPhases = new ArrayList<>();
            try {
                fixture.run(frame -> {
                    if (!frame.startsWith("event: turn.status") || !frame.contains("\"phase\":\"" + phase + "\"")) return;
                    ChatMessageEntity before = fixture.latest();
                    int writes = fixture.snapshots.size();
                    var response = fixture.service.cancelTurn(42L, "temporary-conversation", "turn-tools");
                    assertThat(response.content()).isEqualTo(before.getContent());
                    assertThat(response.metadata()).isEqualTo(fixture.history(before)).containsEntry("phase", phase);
                    assertThat(fixture.snapshots).hasSize(writes);
                    assertThat(Thread.currentThread().isInterrupted()).isFalse();
                    checkedPhases.add(phase);
                });
                assertThat(checkedPhases).containsExactly(phase);
            } finally {
                Thread.interrupted();
            }
        }
    }

    private static Map<String, Object> withStringSequence(Map<String, Object> value) {
        Map<String, Object> result = new LinkedHashMap<>(value);
        result.put("sequence", String.valueOf(value.get("sequence")));
        return result;
    }

    private static Map<String, Object> call(String id, int sequence, String status) {
        return Map.of("turnId", "turn-tools", "callId", id, "sequence", sequence, "tool", "weather", "title", "查询天气", "status", status,
                "summary", "天气工具执行状态", "durationMs", 20);
    }

    private static String step(int sequence, String status) {
        return event("agent.step", Map.of("turnId", "turn-tools", "sequence", sequence, "step", "tool_agent", "status", status));
    }

    private static String reasoning(int sequence, String delta) {
        return event("reasoning.delta", Map.of("turnId", "turn-tools", "sequence", sequence, "step", "writer_agent", "kind", "reasoning", "delta", delta));
    }

    private static String event(String name, Object payload) {
        try { return "event: " + name + "\ndata: " + JSON.writeValueAsString(payload) + "\n\n"; }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> metadata, String key) {
        return (List<Map<String, Object>>) metadata.get(key);
    }

    private static ObjectMapper productionJson() {
        try (var context = new AnnotationConfigApplicationContext(JacksonConfig.class, JacksonAutoConfiguration.class)) {
            return context.getBean(ObjectMapper.class);
        }
    }

    private static void exportFixture(List<String> live, ChatMessageEntity saved, Map<String, Object> history, List<String> replay) throws Exception {
        String filename = System.getProperty("lyle.toolProcessFixture");
        if (filename == null || filename.isBlank()) return;
        Path path = Path.of(filename).toAbsolutePath();
        Files.createDirectories(path.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), Map.of("turnId", "turn-tools", "liveFrames", live,
                "storedMetadata", JSON.readTree(saved.getMetadataJson()), "historyMetadata", JSON.readTree(JSON.writeValueAsString(history)), "replayFrames", replay));
    }

    private static class Fixture {
        final AiServiceClient client;
        final ChatMessageMapper messages = mock(ChatMessageMapper.class);
        final List<ChatMessageEntity> snapshots = new ArrayList<>();
        final List<String> frames = new ArrayList<>();
        final ChatServiceImpl service;

        Fixture() { this(mock(AiServiceClient.class)); }
        Fixture(AiServiceClient client) {
            this.client = client;
            AiConfigService config = mock(AiConfigService.class);
            when(config.currentConfigMap()).thenReturn(Map.of());
            service = new ChatServiceImpl(client, config, messages, mock(ConversationMapper.class), JSON);
            doAnswer(invocation -> {
                ChatMessageEntity source = invocation.getArgument(0);
                ChatMessageEntity saved = new ChatMessageEntity();
                saved.setRole(source.getRole());
                saved.setConversationId(source.getConversationId());
                saved.setTurnId(source.getTurnId());
                saved.setContent(source.getContent());
                saved.setMetadataJson(source.getMetadataJson());
                snapshots.add(saved);
                return 1;
            }).when(messages).updateById(any(ChatMessageEntity.class));
        }
        ChatMessageEntity latest() { return snapshots.get(snapshots.size() - 1); }
        Map<String, Object> history(ChatMessageEntity saved) {
            when(messages.selectByConversationId("temporary-conversation")).thenReturn(List.of(saved));
            return service.listMessages(42L, "temporary-conversation").get(0).metadata();
        }
        void run(java.util.function.Consumer<String> observer) {
            service.stream(42L, new ChatRequestDTO("temporary-conversation", "turn-tools", null, "天气", List.of()), frame -> {
                frames.add(frame);
                observer.accept(frame);
            });
        }
    }
}
