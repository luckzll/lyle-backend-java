package com.example.aiapp.service.impl;

import com.example.aiapp.domain.dto.ChatRequestDTO;
import com.example.aiapp.domain.entity.ChatMessageEntity;
import com.example.aiapp.infrastructure.client.AiServiceClient;
import com.example.aiapp.mapper.ChatMessageMapper;
import com.example.aiapp.mapper.ConversationMapper;
import com.example.aiapp.service.AiConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatStreamProgressTest {
    @Test
    void forwardsOnlySafeLatestStepsAndPreservesProgressAcrossFinalMetaAndHistory() {
        Fixture fixture = new Fixture();
        when(fixture.client.streamChat(any())).thenReturn(Flux.concat(
                Flux.just(": heartbeat\r\n\r", "\n",
                        "event:agent.step\r\ndata:{\"turnId\":\"turn-progress\",\"sequence\":1,\"step\":\"rag_agent\",\"status\":\"running\",\"rawPrompt\":\"PRIVATE\"}\r\n\r\n"),
                Flux.defer(() -> {
                    assertThat(fixture.latest().getMetadataJson()).contains("\"status\":\"running\"");
                    return Flux.just(
                            "event: agent.step\ndata: {\"sequence\":3,\"step\":\"rag_agent\",\"status\":\"completed\",\"durationMs\":1234,\"timings\":{\"embedding_ms\":25,\"secret\":\"PRIVATE\",\"negative\":-1},\"summary\":\"命中 5 段\"}\n\n",
                            "event: agent.step\ndata: {\"sequence\":2,\"step\":\"rag_agent\",\"status\":\"running\"}\n\n",
                            "event: agent.step\ndata: {\"turnId\":\"other-turn\",\"sequence\":4,\"step\":\"rag_agent\",\"status\":\"failed\"}\n\n",
                            "event: agent.step\ndata: {\"sequence\":5,\"step\":\"untrusted_agent\",\"status\":\"running\"}\n\n",
                            "event: meta\ndata: {\"agentProgress\":[{\"step\":\"rag_agent\",\"sequence\":99,\"status\":\"failed\"}],\"agentTrace\":[{\"agent\":\"rag_agent\",\"timings\":{\"embedding_ms\":25}}]}\n\n",
                            "event: delta\ndata: {\"text\":\"完整答案\"}\n\n",
                            "event: done\ndata: {}\n\n");
                })
        ));
        fixture.run();
        String output = String.join("", fixture.frames);
        assertThat(output).contains(": keep-alive\n\n", "\"status\":\"completed\"")
                .doesNotContain("PRIVATE", "other-turn", "untrusted_agent", "\"sequence\":2", "\"sequence\":99");
        ChatMessageEntity saved = fixture.latest();
        when(fixture.messages.selectByConversationId("temporary-conversation")).thenReturn(List.of(saved));
        var history = fixture.service.listMessages(42L, "temporary-conversation");
        assertThat(history.get(0).metadata().get("agentProgress").toString())
                .contains("sequence=3", "status=completed", "embedding_ms=25")
                .doesNotContain("sequence=2", "negative", "secret");
        assertThat(saved.getContent()).isEqualTo("完整答案");
    }

    @Test
    void checkpointsTextAtBoundedFrequencyAndPersistsCompletionBeforeDone() {
        Fixture fixture = new Fixture();
        when(fixture.client.streamChat(any())).thenReturn(Flux.concat(
                Flux.just("event: delta\ndata: {\"text\":\"首字\"}\n\n"),
                Flux.defer(() -> {
                    assertThat(fixture.latest().getContent()).isEqualTo("首字");
                    return Flux.range(0, 100).map(index -> "event: delta\ndata: {\"text\":\"文\"}\n\n");
                }),
                Flux.just("event: delta\ndata: {\"text\":\"末尾\"}\n\n"),
                Flux.just(": heartbeat\n\n").delaySubscription(Duration.ofMillis(1100)),
                Flux.defer(() -> {
                    assertThat(fixture.latest().getContent()).endsWith("末尾");
                    assertThat(fixture.latest().getMetadataJson()).contains("RESPONDING");
                    return Flux.just("event: done\ndata: {}\n\n");
                })
        ));
        fixture.run();
        assertThat(fixture.snapshots).hasSizeLessThanOrEqualTo(5);
        assertThat(fixture.latest().getContent()).isEqualTo("首字" + "文".repeat(100) + "末尾");
        assertThat(fixture.frames.get(fixture.frames.size() - 1)).startsWith("event: done");
    }

    @Test
    void heartbeatOnlyOrTruncatedStreamNeverReportsSuccessfulCompletion() {
        for (String upstream : List.of(": heartbeat\n\n", "event: delta\ndata: {\"text\":\"部分答案\"}\n\n",
                "event: turn.status\ndata: {\"phase\":\"COMPLETED\"}\n\n")) {
            Fixture fixture = new Fixture();
            when(fixture.client.streamChat(any())).thenReturn(Flux.just(upstream));
            assertThatThrownBy(fixture::run).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("completion event");
            assertThat(fixture.latest().getMetadataJson()).contains("FAILED");
            assertThat(String.join("", fixture.frames)).doesNotContain("\"phase\":\"COMPLETED\"", "event: done")
                    .contains("event: error", "AI 回复连接中断");
            if (upstream.contains("部分答案")) assertThat(fixture.latest().getContent()).isEqualTo("部分答案");
        }
    }

    @Test
    void upstreamReadTimeoutRetainsTextAndReportsActionableChineseErrorWithoutRetry() {
        Fixture fixture = new Fixture();
        when(fixture.client.streamChat(any())).thenReturn(Flux.concat(
                Flux.just("event: agent.step\ndata: {\"turnId\":\"turn-progress\",\"sequence\":4,\"step\":\"writer_agent\",\"status\":\"running\"}\n\n"),
                Flux.just("event: delta\ndata: {\"text\":\"已生成内容\"}\n\n"),
                Flux.error(io.netty.handler.timeout.ReadTimeoutException.INSTANCE)
        ));
        assertThatThrownBy(fixture::run).isInstanceOf(RuntimeException.class);
        assertThat(fixture.latest().getContent()).isEqualTo("已生成内容");
        assertThat(fixture.latest().getMetadataJson()).contains("AI 服务响应超时", "FAILED");
        assertThat(fixture.latest().getMetadataJson()).contains("\"sequence\":5", "\"status\":\"failed\"")
                .doesNotContain("\"status\":\"running\"");
        assertThat(String.join("", fixture.frames)).contains("event: error", "AI 服务响应超时");
        org.mockito.Mockito.verify(fixture.client).streamChat(any());
    }

    @Test
    void cancellationClosesOnlyRunningStepsAndPreservesCompletedEvidence() throws Exception {
        Fixture fixture = new Fixture();
        ChatMessageEntity saved = new ChatMessageEntity();
        saved.setRole("assistant");
        saved.setConversationId("temporary-conversation");
        saved.setTurnId("turn-progress");
        saved.setContent("已显示正文");
        saved.setMetadataJson(new ObjectMapper().writeValueAsString(Map.of("phase", "RESPONDING", "agentProgress", List.of(
                Map.of("turnId", "turn-progress", "sequence", 3, "step", "rag_agent", "status", "completed", "summary", "命中 5 段"),
                Map.of("turnId", "turn-progress", "sequence", 4, "step", "writer_agent", "status", "running")
        ))));
        when(fixture.messages.selectByConversationIdAndTurnId("temporary-conversation", "turn-progress")).thenReturn(saved);
        var response = fixture.service.cancelTurn(42L, "temporary-conversation", "turn-progress");
        assertThat(response.metadata().get("phase")).isEqualTo("FAILED");
        assertThat(response.metadata().get("agentProgress").toString())
                .contains("status=completed", "命中 5 段", "status=failed", "sequence=5", "用户停止")
                .doesNotContain("status=running", "reviewer_agent");
        assertThat(response.content()).startsWith("已显示正文");
    }

    @Test
    void malformedFrameIsNotSilentlyForwardedOrSavedAsSuccess() {
        Fixture fixture = new Fixture();
        when(fixture.client.streamChat(any())).thenReturn(Flux.just("event: delta\ndata: not-json\n\n"));
        assertThatThrownBy(fixture::run).isInstanceOf(IllegalStateException.class).hasMessageContaining("Invalid AI");
        assertThat(String.join("", fixture.frames)).doesNotContain("not-json", "event: done");
        assertThat(fixture.latest().getMetadataJson()).contains("FAILED");
    }

    private static class Fixture {
        final AiServiceClient client = mock(AiServiceClient.class);
        final ChatMessageMapper messages = mock(ChatMessageMapper.class);
        final List<ChatMessageEntity> snapshots = new ArrayList<>();
        final List<String> frames = new ArrayList<>();
        final ChatServiceImpl service;

        Fixture() {
            AiConfigService config = mock(AiConfigService.class);
            when(config.currentConfigMap()).thenReturn(Map.of());
            service = new ChatServiceImpl(client, config, messages, mock(ConversationMapper.class), new ObjectMapper());
            doAnswer(invocation -> {
                ChatMessageEntity source = invocation.getArgument(0);
                ChatMessageEntity snapshot = new ChatMessageEntity();
                snapshot.setId(source.getId());
                snapshot.setRole(source.getRole());
                snapshot.setConversationId(source.getConversationId());
                snapshot.setTurnId(source.getTurnId());
                snapshot.setContent(source.getContent());
                snapshot.setMetadataJson(source.getMetadataJson());
                snapshots.add(snapshot);
                return 1;
            }).when(messages).updateById(any(ChatMessageEntity.class));
        }

        ChatMessageEntity latest() { return snapshots.get(snapshots.size() - 1); }

        void run() {
            service.stream(42L, new ChatRequestDTO("temporary-conversation", "turn-progress", null, "你好", List.of()), frame -> {
                if (frame.startsWith("event: done")) assertThat(latest().getMetadataJson()).contains("COMPLETED");
                frames.add(frame);
            });
        }
    }
}
