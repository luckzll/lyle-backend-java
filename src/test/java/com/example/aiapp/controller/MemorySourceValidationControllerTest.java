package com.example.aiapp.controller;

import com.example.aiapp.config.AiServiceProperties;
import com.example.aiapp.service.MemorySourceValidationService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemorySourceValidationControllerTest {

    @Test
    void requiresInternalTokenAndForwardsUserScopedSources() {
        MemorySourceValidationService service = mock(MemorySourceValidationService.class);
        AiServiceProperties properties = new AiServiceProperties(
                "http://localhost:8000", "strong-internal-token", 1024, 1000, 30
        );
        MemorySourceValidationController controller = new MemorySourceValidationController(service, properties);
        List<Map<String, Object>> sources = List.of(
                Map.of("id", "source-1", "type", "CONVERSATION", "conversationId", "66", "messageId", "8801")
        );
        when(service.validate("42", sources)).thenReturn(List.of(Map.of("id", "source-1", "available", true)));

        assertThatThrownBy(() -> controller.validate("wrong", Map.of("userId", "42", "sources", sources)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.validate("", Map.of("userId", "42", "sources", sources)))
                .isInstanceOf(ResponseStatusException.class);

        Map<String, Object> result = controller.validate(
                "strong-internal-token",
                Map.of("userId", "42", "sources", sources)
        );

        assertThat(result.get("items")).isEqualTo(List.of(Map.of("id", "source-1", "available", true)));
        verify(service).validate("42", sources);
    }
}
