package com.example.aiapp.controller;

import com.example.aiapp.billing.BillingService;
import com.example.aiapp.infrastructure.client.AiServiceClient;
import com.example.aiapp.security.CurrentUser;
import com.example.aiapp.service.AiConfigService;
import com.example.aiapp.usage.UsageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalWorkspaceDocumentControllerTest {
    private final AiServiceClient ai = mock(AiServiceClient.class);
    private final AiConfigService config = mock(AiConfigService.class);
    private final UsageService usage = mock(UsageService.class);
    private final BillingService billing = mock(BillingService.class);
    private final LocalWorkspaceController controller = new LocalWorkspaceController(ai, config, usage, billing, new ObjectMapper());
    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new CurrentUser(42L, "test", "USER"), null));
    }
    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }
    @Test void requiresAuthenticationBeforeParsing() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> controller.extractDocument(new LocalWorkspaceController.Document("report.pdf", "dGVzdA=="))).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(ai, config, usage, billing);
    }
    @Test void relaysBytesWithoutChatMemoryOrModelSideEffectsAndDisablesCaching() {
        authenticate();
        Map<String, Object> parsed = Map.of("text", "真实文档内容", "format", "docx", "truncated", false, "warnings", java.util.List.of());
        when(ai.extractLocalWorkspaceDocument(Map.of("name", "notes.docx", "data", "dGVzdA=="))).thenReturn(parsed);
        var response = controller.extractDocument(new LocalWorkspaceController.Document("notes.docx", "dGVzdA=="));
        assertThat(response.getBody()).isEqualTo(parsed);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        verifyNoInteractions(config, usage, billing);
    }
    @ParameterizedTest @ValueSource(strings={"../notes.docx", "C:\\report.pdf", ".secret.xlsx", "legacy.doc", "legacy.ppt", "file.exe"})
    void rejectsUntrustedNamesBeforeCallingService(String name) {
        authenticate();
        assertThatThrownBy(() -> controller.extractDocument(new LocalWorkspaceController.Document(name, "dGVzdA=="))).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(ai, config, usage, billing);
    }
    @ParameterizedTest @ValueSource(strings={"data:application/pdf;base64,dGVzdA==", "dGVzdA", "@@@=", "dGVzdB=="})
    void rejectsInvalidOrNonCanonicalBase64(String data) {
        authenticate();
        assertThatThrownBy(() -> controller.extractDocument(new LocalWorkspaceController.Document("report.pdf", data))).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(ai);
    }
    @Test void rejectsOversizedBytesEvenWithinEncodedLengthLimit() {
        authenticate();
        String data = java.util.Base64.getEncoder().encodeToString(new byte[5 * 1024 * 1024 + 1]);
        assertThatThrownBy(() -> controller.extractDocument(new LocalWorkspaceController.Document("report.pdf", data))).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(ai);
    }
}
