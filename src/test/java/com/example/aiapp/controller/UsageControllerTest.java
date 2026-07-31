package com.example.aiapp.controller;

import com.example.aiapp.config.UsageProperties;
import com.example.aiapp.security.CurrentUser;
import com.example.aiapp.usage.UsageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UsageControllerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void usageReadsAreAlwaysScopedToTheAuthenticatedUser() {
        UsageService service = mock(UsageService.class);
        UsageController controller = controller(service);
        authenticate(42L, "user-a");
        when(service.summary(42L, "today")).thenReturn(Map.of("totalTokens", 120L));
        when(service.recentTurns(42L, "month", 20)).thenReturn(List.of(Map.of("turnId", "a-1")));

        assertThat(controller.summary("today")).containsEntry("totalTokens", 120L);
        assertThat(controller.turns("month", 20)).containsExactly(Map.of("turnId", "a-1"));

        verify(service).summary(42L, "today");
        verify(service).recentTurns(42L, "month", 20);
    }

    @Test
    void policyUpdateIgnoresAnyClientSuppliedUserIdentity() {
        UsageService service = mock(UsageService.class);
        UsageController controller = controller(service);
        authenticate(84L, "user-b");
        Map<String, Long> request = Map.of(
                "dailySoftTokenLimit", 100L,
                "dailyHardTokenLimit", 200L,
                "userId", 42L
        );
        when(service.updatePolicy(eq(84L), eq(request))).thenReturn(Map.of("dailyHardTokenLimit", 200L));

        assertThat(controller.updatePolicy(request)).containsEntry("dailyHardTokenLimit", 200L);

        verify(service).updatePolicy(84L, request);
    }

    private static UsageController controller(UsageService service) {
        return new UsageController(
                service,
                new UsageProperties(true, true, true, "strong-internal-token", 10)
        );
    }

    private static void authenticate(long userId, String username) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new CurrentUser(userId, username, "USER"),
                        null
                )
        );
    }
}
