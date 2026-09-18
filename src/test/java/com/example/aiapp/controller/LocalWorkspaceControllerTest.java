package com.example.aiapp.controller;

import com.example.aiapp.billing.BillingService;
import com.example.aiapp.infrastructure.client.AiServiceClient;
import com.example.aiapp.service.AiConfigService;
import com.example.aiapp.usage.UsageService;
import com.example.aiapp.security.CurrentUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import reactor.core.publisher.Flux;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LocalWorkspaceControllerTest {
    @Test
    void statelessRelayUsesAuthenticatedIdentityAndReleasesBothReservations() throws Exception {
        AiServiceClient ai = mock(AiServiceClient.class);
        AiConfigService config = mock(AiConfigService.class);
        UsageService usage = mock(UsageService.class);
        BillingService billing = mock(BillingService.class);
        when(config.currentConfigMap()).thenReturn(Map.of());
        when(usage.reserve(eq(42L), isNull(), anyString())).thenReturn(new UsageService.Reservation("r", "t", 10, 10000, 2000, 10000));
        when(billing.reserveCommercial(eq(42L), anyString())).thenReturn("b");
        when(ai.streamLocalWorkspace(anyMap())).thenAnswer(invocation -> {
            Map<String, Object> sent = invocation.getArgument(0);
            assertThat(sent.get("user_id")).isEqualTo("42");
            assertThat(sent).doesNotContainKeys("conversation_id", "attachments");
            return Flux.just("event: result\ndata: {}\n\n");
        });
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new CurrentUser(42L, "test", "USER"), null));
        try {
            var controller = new LocalWorkspaceController(ai, config, usage, billing, new ObjectMapper());
            var response = controller.step(new LocalWorkspaceController.Step(List.of(Map.of("role", "user", "content", "code"))));
            var output = new ByteArrayOutputStream(); response.getBody().writeTo(output);
            assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
            assertThat(output.toString()).contains("event: result");
            verify(usage).releaseReservation("r"); verify(billing).releaseCommercialReservation("b");
            doReturn(Flux.error(new RuntimeException("PRIVATE_CODE"))).when(ai).streamLocalWorkspace(anyMap());
            output.reset(); response.getBody().writeTo(output);
            assertThat(output.toString()).contains("event: error").doesNotContain("PRIVATE_CODE");
        } finally { SecurityContextHolder.clearContext(); }
    }
}
