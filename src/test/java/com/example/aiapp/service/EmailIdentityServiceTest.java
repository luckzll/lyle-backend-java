package com.example.aiapp.service;

import com.example.aiapp.domain.dto.AuthRequestDTO;
import com.example.aiapp.domain.vo.AuthResponseVO;
import com.example.aiapp.domain.vo.UserVO;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class EmailIdentityServiceTest {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    AuthService auth = mock(AuthService.class);
    EmailIdentityService service = new EmailIdentityService(jdbc, auth);
    @Test void existingEmailUsesOriginalUserId() {
        when(jdbc.queryForList(anyString(), eq(Long.class), eq("a@example.com"))).thenReturn(List.of(7L));
        service.login("a@example.com", "browser", "ip");
        verify(auth).loginVerifiedUser(7L, "browser", "ip");
        verify(auth, never()).register(any(), any(), any());
    }
    @Test void newEmailCreatesUserAndVerifiedIdentity() {
        when(jdbc.queryForList(anyString(), eq(Long.class), anyString())).thenReturn(List.of());
        when(auth.register(any(AuthRequestDTO.class), anyString(), anyString())).thenReturn(
                new AuthService.AuthenticatedSession(new AuthResponseVO(new UserVO(8L, "generated", "USER"), "access"), "refresh"));
        service.login("new@example.com", "browser", "ip");
        verify(jdbc).update(startsWith("INSERT INTO auth_email_identity"), eq("new@example.com"), eq(8L));
    }
    @Test void bindingDoesNotCreateAnotherUser() {
        when(jdbc.queryForList(anyString(), eq(String.class), eq(7L))).thenReturn(List.of());
        service.bind(7L, "a@example.com");
        verify(jdbc).update(startsWith("INSERT INTO auth_email_identity"), eq("a@example.com"), eq(7L));
        verifyNoInteractions(auth);
    }
    @Test void bindingCannotReplaceExistingEmail() {
        when(jdbc.queryForList(anyString(), eq(String.class), eq(7L))).thenReturn(List.of("old@example.com"));
        assertThatThrownBy(() -> service.bind(7L, "new@example.com")).hasMessageContaining("已绑定");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
}
