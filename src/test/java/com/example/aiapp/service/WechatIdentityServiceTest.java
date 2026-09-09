package com.example.aiapp.service;

import com.example.aiapp.config.WechatLoginProperties;
import com.example.aiapp.domain.vo.AuthResponseVO;
import com.example.aiapp.domain.vo.UserVO;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class WechatIdentityServiceTest {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    AuthService auth = mock(AuthService.class);
    EmailIdentityService email = mock(EmailIdentityService.class);
    WechatIdentityService service = new WechatIdentityService(jdbc,auth,email,new WechatLoginProperties(true,"app",null,null));
    @Test void returningWechatUsesOriginalAccountWithoutEmail() {
        when(jdbc.queryForList(anyString(),eq(Long.class),eq("app"),eq("open"))).thenReturn(List.of(7L));
        service.login("open","browser","ip");
        verify(auth).loginVerifiedUser(7L,"browser","ip"); verifyNoInteractions(email);
    }
    @Test void firstWechatLinksVerifiedEmailAccountInsteadOfCreatingAnother() {
        when(jdbc.queryForList(anyString(),eq(Long.class),eq("app"),eq("open"))).thenReturn(List.of());
        when(email.login("old@example.com","browser","ip")).thenReturn(new AuthService.AuthenticatedSession(new AuthResponseVO(new UserVO(7L,"zhoulele","USER"),"access"),"refresh"));
        service.link("open","old@example.com","browser","ip");
        verify(jdbc).update(startsWith("INSERT INTO auth_wechat_identity"),eq("app"),eq("open"),eq(7L));
        verifyNoInteractions(auth);
    }
    @Test void doesNotReplaceExistingWechatOwner() {
        when(jdbc.queryForList(anyString(),eq(Long.class),eq("app"),eq("open"))).thenReturn(List.of(7L));
        when(email.email(7L)).thenReturn("old@example.com");
        assertThatThrownBy(() -> service.link("open","other@example.com","browser","ip")).hasMessageContaining("其他账号");
        verify(email,never()).login(anyString(),anyString(),anyString()); verifyNoInteractions(auth);
    }
}
