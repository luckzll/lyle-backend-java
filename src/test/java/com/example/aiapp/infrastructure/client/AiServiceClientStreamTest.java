package com.example.aiapp.infrastructure.client;

import com.example.aiapp.domain.dto.AiChatRequestDTO;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiServiceClientStreamTest {
    @Test
    void decodesSplitChineseAndEmojiAcrossEveryNetworkByte() {
        String expected = ": keep-alive\n\nevent: delta\ndata: {\"text\":\"中文🙂\"}\n\n";
        byte[] bytes = expected.getBytes(StandardCharsets.UTF_8);
        AiServiceClient client = client(Flux.range(0, bytes.length).map(index -> new byte[]{bytes[index]}));
        assertThat(String.join("", client.streamChat(request()).collectList().block())).isEqualTo(expected);
        // A second subscription must have fresh decoder state.
        assertThat(String.join("", client.streamChat(request()).collectList().block())).isEqualTo(expected);
    }

    @Test
    void rejectsAnIncompleteUtf8SequenceAtEndOfStream() {
        byte[] character = "文".getBytes(StandardCharsets.UTF_8);
        AiServiceClient client = client(Flux.just(Arrays.copyOf(character, character.length - 1)));
        assertThatThrownBy(() -> client.streamChat(request()).collectList().block())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("UTF-8");
    }

    private AiServiceClient client(Flux<byte[]> bytes) {
        DefaultDataBufferFactory buffers = new DefaultDataBufferFactory();
        WebClient webClient = WebClient.builder().exchangeFunction(request -> Mono.just(
                ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", "text/event-stream")
                        .body(bytes.map(buffers::wrap)).build()
        )).build();
        return new AiServiceClient(webClient);
    }

    private AiChatRequestDTO request() {
        return new AiChatRequestDTO("42", "conversation", "turn", null, "你好", List.of(), true, Map.of());
    }
}
