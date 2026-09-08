package com.example.aiapp.controller;

import com.example.aiapp.infrastructure.client.AiServiceClient;
import com.example.aiapp.security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class MemorySchemaControllerTest {
    @Test
    void schemaEndpointsUseAuthenticatedUserNotRequestUser() {
        AiServiceClient client = mock(AiServiceClient.class);
        MemoryController controller = new MemoryController(client);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(42L, "lyle", "USER"), null));
        try {
            Map<String, Object> request = Map.of("userId", "other-user", "question", "我的偏好", "mode", "agent");
            controller.schema();
            controller.rebuildSchema();
            controller.recallSchema(request);
            controller.facts();
            controller.reviseFact("fact-1", Map.of("expectedRevision", 1, "status", "disabled", "userId", "other-user"));
            verify(client).getMemorySchema("42");
            verify(client).rebuildMemorySchema("42");
            verify(client).recallMemorySchema("42", request);
            verify(client).getMemoryFacts("42");
            verify(client).reviseMemoryFact(eq("42"), eq("fact-1"), anyMap());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void schemaRequiresAuthenticatedUser() {
        SecurityContextHolder.clearContext();
        AiServiceClient client = mock(AiServiceClient.class);
        MemoryController controller = new MemoryController(client);
        assertThatThrownBy(controller::schema).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(client);
    }
}
