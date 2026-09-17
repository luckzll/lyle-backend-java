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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChatStreamReasoningTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void reasoningIsLiveAndIndependentOfAnswerAndSurvivesHistoryAndSnapshotReplay() throws Exception {
        Fixture fixture = new Fixture();
        String first = event("reasoning.delta", reasoning(1, "先检查 "));
        when(fixture.client.streamChat(any())).thenReturn(Flux.concat(
                Flux.just(first.substring(0, 19), first.substring(19)),
                Flux.defer(() -> {
                    assertThat(fixture.latest().getContent()).isEmpty();
                    assertThat(fixture.latest().getMetadataJson()).contains("先检查 ");
                    return Flux.just(event("reasoning.delta", reasoning(2, "\n再核对证据。")),
                            event("delta", Map.of("text", "最终答案")),
                            event("meta", Map.of("reasoning", Map.of("text", "伪造覆盖", "kind", "reasoning", "sequence", 99),
                                    "processStartedAt", 1, "processCompletedAt", 2)),
                            event("done", Map.of("reasoning", Map.of("text", "完成覆盖", "kind", "reasoning", "sequence", 100))));
                })
        ));
        fixture.run(false);
        ChatMessageEntity saved = fixture.latest();
        assertThat(saved.getContent()).isEqualTo("最终答案");
        Map<String, Object> metadata = metadata(saved);
        assertThat(metadata.get("reasoning")).isEqualTo(Map.of("text", "先检查 \n再核对证据。", "kind", "reasoning", "sequence", 2));
        assertThat(((Number) metadata.get("processCompletedAt")).longValue())
                .isGreaterThanOrEqualTo(((Number) metadata.get("processStartedAt")).longValue()).isGreaterThan(2);
        assertThat(String.join("", fixture.frames)).contains("event: reasoning.delta", "processCompletedAt")
                .doesNotContain("伪造覆盖", "完成覆盖");
        when(fixture.messages.selectByConversationId("temporary-conversation")).thenReturn(List.of(saved));
        assertThat(fixture.service.listMessages(42L, "temporary-conversation").get(0).metadata())
                .containsEntry("reasoning", Map.of("text", "先检查 \n再核对证据。", "kind", "reasoning", "sequence", 2L))
                .containsEntry("processStartedAt", metadata.get("processStartedAt"))
                .containsEntry("processCompletedAt", metadata.get("processCompletedAt"));
        when(fixture.messages.selectByConversationIdAndTurnId("temporary-conversation", "turn-reasoning")).thenReturn(saved);
        fixture.frames.clear();
        fixture.run(false);
        assertThat(String.join("", fixture.frames)).contains("event: meta", "先检查 ", "再核对证据。")
                .doesNotContain("event: reasoning.delta");
        verify(fixture.client, times(1)).streamChat(any());
    }

    @Test
    void rejectsDuplicatesCrossTurnAndMalformedReasoningBeforeForwardingOrPersistence() throws Exception {
        Fixture fixture = new Fixture();
        List<String> upstream = new ArrayList<>();
        upstream.add(event("reasoning.delta", reasoning(2, "真实内容")));
        upstream.add(event("reasoning.delta", reasoning(2, "重复内容")));
        upstream.add(event("reasoning.delta", reasoning(1, "过期内容")));
        for (Map.Entry<String, Object> invalid : Map.<String, Object>of(
                "turnId", "other-turn", "step", "private_agent", "kind", "private",
                "sequence", 1.5, "delta", Map.of("private", "PRIVATE")).entrySet()) {
            Map<String, Object> data = new LinkedHashMap<>(reasoning(3, "PRIVATE"));
            data.put(invalid.getKey(), invalid.getValue());
            upstream.add(event("reasoning.delta", data));
        }
        Map<String, Object> missingTurn = new LinkedHashMap<>(reasoning(3, "PRIVATE"));
        missingTurn.remove("turnId");
        upstream.add(event("reasoning.delta", missingTurn));
        Map<String, Object> mixedKind = new LinkedHashMap<>(reasoning(3, "PRIVATE"));
        mixedKind.put("kind", "summary");
        upstream.add(event("reasoning.delta", mixedKind));
        upstream.add(event("done", Map.of()));
        when(fixture.client.streamChat(any())).thenReturn(Flux.fromIterable(upstream));
        fixture.run(false);
        assertThat(String.join("", fixture.frames)).doesNotContain("PRIVATE", "重复内容", "过期内容", "other-turn", "private_agent");
        assertThat(metadata(fixture.latest()).get("reasoning"))
                .isEqualTo(Map.of("text", "真实内容", "kind", "reasoning", "sequence", 2));
        assertThat(fixture.frames.stream().filter(frame -> frame.startsWith("event: reasoning.delta"))).hasSize(1);
    }

    @Test
    void capsReasoningAndCheckpointsWithoutWritingForEveryToken() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.client.streamChat(any())).thenReturn(Flux.concat(
                Flux.range(1, 100).map(sequence -> event("reasoning.delta", reasoning(sequence, "文".repeat(200)))),
                Flux.just(event("done", Map.of()))));
        fixture.run(false);
        Map<?, ?> reasoning = (Map<?, ?>) metadata(fixture.latest()).get("reasoning");
        assertThat(reasoning.get("text")).isEqualTo("文".repeat(16_000));
        assertThat(reasoning.get("sequence")).isEqualTo(80);
        assertThat(fixture.snapshots).hasSizeLessThanOrEqualTo(4);
        assertThat(fixture.latest().getContent()).isEmpty();
    }

    @Test
    void interruptedReasoningIsRetainedAndRetryStartsANewSnapshot() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.client.streamChat(any())).thenReturn(Flux.just(event("reasoning.delta", reasoning(1, "已检查部分证据"))));
        assertThatThrownBy(() -> fixture.run(false)).hasMessageContaining("completion event");
        ChatMessageEntity saved = fixture.latest();
        assertThat(metadata(saved)).containsEntry("phase", "FAILED").containsKeys("reasoning", "processCompletedAt");
        when(fixture.messages.selectByConversationIdAndTurnId("temporary-conversation", "turn-reasoning")).thenReturn(saved);
        var cancelled = fixture.service.cancelTurn(42L, "temporary-conversation", "turn-reasoning");
        assertThat(cancelled.metadata().get("reasoning"))
                .isEqualTo(Map.of("text", "已检查部分证据", "kind", "reasoning", "sequence", 1L));
        ChatMessageEntity user = new ChatMessageEntity();
        user.setContent("你好");
        when(fixture.messages.selectUserByConversationIdAndTurnId("temporary-conversation", "turn-reasoning")).thenReturn(user);
        when(fixture.client.streamChat(any())).thenReturn(Flux.just(event("delta", Map.of("text", "重试答案")), event("done", Map.of())));
        fixture.frames.clear();
        fixture.run(true);
        assertThat(metadata(fixture.latest())).doesNotContainKey("reasoning");
        assertThat(fixture.latest().getContent()).isEqualTo("重试答案");
        assertThat(String.join("", fixture.frames)).doesNotContain("已检查部分证据", "event: reasoning.delta");
    }

    private static Map<String, Object> reasoning(int sequence, String delta) {
        return Map.of("turnId", "turn-reasoning", "sequence", sequence, "step", "writer_agent", "kind", "reasoning", "delta", delta);
    }

    private static String event(String name, Object data) {
        try {
            return "event: " + name + "\ndata: " + JSON.writeValueAsString(data) + "\n\n";
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> metadata(ChatMessageEntity message) throws Exception {
        return JSON.readValue(message.getMetadataJson(), Map.class);
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

        void run(boolean retry) {
            service.stream(42L, new ChatRequestDTO("temporary-conversation", "turn-reasoning", null,
                    "你好", List.of(), List.of(), retry), frames::add);
        }
    }
}
