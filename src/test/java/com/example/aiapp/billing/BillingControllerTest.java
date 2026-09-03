package com.example.aiapp.billing;

import com.example.aiapp.security.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BillingControllerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void everyUserResourceIsScopedByTheAuthenticatedIdentity() {
        BillingService service = mock(BillingService.class);
        BillingController controller = new BillingController(service);
        authenticate(42L);
        when(service.account(42L)).thenReturn(Map.of("availableCreditMicros", 10L));
        when(service.orders(42L, 20)).thenReturn(List.of(Map.of("orderNo", "L-42")));
        when(service.order(42L, "L-42")).thenReturn(Map.of("orderNo", "L-42"));
        when(service.closeOrder(42L, "L-42")).thenReturn(Map.of("orderNo", "L-42", "status", "CLOSED"));

        assertThat(controller.account()).containsEntry("availableCreditMicros", 10L);
        assertThat(controller.orders(20)).containsExactly(Map.of("orderNo", "L-42"));
        assertThat(controller.order("L-42")).containsEntry("orderNo", "L-42");
        assertThat(controller.close("L-42")).containsEntry("status", "CLOSED");
        controller.delete("L-42");

        verify(service).account(42L);
        verify(service).orders(42L, 20);
        verify(service).order(42L, "L-42");
        verify(service).closeOrder(42L, "L-42");
        verify(service).deleteOrder(42L, "L-42");
    }

    @Test
    void createOrderCannotAcceptAClientSuppliedUserId() {
        BillingService service = mock(BillingService.class);
        BillingController controller = new BillingController(service);
        authenticate(84L);
        BillingRequests.CreateOrder request =
                new BillingRequests.CreateOrder("PRO-30D-V1", "NEW_PURCHASE", "request-1");
        when(service.createOrder(84L, request)).thenReturn(Map.of("orderNo", "L-84"));

        assertThat(controller.createOrder(request)).containsEntry("orderNo", "L-84");
        verify(service).createOrder(84L, request);
    }

    private static void authenticate(long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new CurrentUser(userId, "user-" + userId, "USER"),
                        null
                )
        );
    }
}
