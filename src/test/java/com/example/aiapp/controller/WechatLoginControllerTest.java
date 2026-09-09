package com.example.aiapp.controller;

import com.example.aiapp.config.AuthProperties;
import com.example.aiapp.config.WechatLoginProperties;
import com.example.aiapp.domain.vo.AuthResponseVO;
import com.example.aiapp.domain.vo.UserVO;
import com.example.aiapp.security.*;
import com.example.aiapp.service.*;
import jakarta.servlet.http.Cookie;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WechatLoginControllerTest {
    WechatLoginFlow flows = mock(WechatLoginFlow.class);
    WechatIdentityService identities = mock(WechatIdentityService.class);
    EmailCodeService codes = mock(EmailCodeService.class);
    HumanVerificationService captcha = mock(HumanVerificationService.class);
    LegacyAccountMigration migration = mock(LegacyAccountMigration.class);
    AuthProperties auth = mock(AuthProperties.class);
    WechatLoginProperties props = new WechatLoginProperties(true,"wx1234567890123456","a".repeat(32),"https://luckyzll.site/api/auth/wechat/callback");
    MockMvc mvc;
    Cookie cookie = new Cookie("lyle_wechat_flow", "b".repeat(43));
    @BeforeEach void setup() {
        when(flows.legacyUser(any())).thenReturn(null);
        when(auth.allowedOrigins()).thenReturn("https://luckyzll.site");
        when(auth.refreshCookieName()).thenReturn("lyle_refresh"); when(auth.refreshTtlDays()).thenReturn(14L); when(auth.secureCookie()).thenReturn(true);
        mvc = MockMvcBuilders.standaloneSetup(new WechatLoginController(props,auth,flows,identities,codes,captcha,migration))
                .addFilters(new RefreshOriginFilter(auth)).build();
    }
    @Test void startCookieIsHttpOnlySecureAndNoSecretsInBody() throws Exception {
        when(flows.start(null)).thenReturn(new WechatLoginFlow.Start(cookie.getValue(),"https://open.weixin.qq.com/connect/qrconnect?state=test",300));
        mvc.perform(post("/auth/wechat/start").header("Origin","https://luckyzll.site").header("Host","luckyzll.site"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.browserToken").doesNotExist())
                .andExpect(header().string("Cache-Control","no-store"))
                .andExpect(header().string("Set-Cookie",org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.containsString("HttpOnly"),org.hamcrest.Matchers.containsString("Secure"),org.hamcrest.Matchers.containsString("SameSite=Lax"))));
    }
    @Test void crossOriginAndMissingOriginAreRejectedBeforeAnyFlowAccess() throws Exception {
        for (String endpoint : new String[]{"start","cancel","complete","email/code","email/login"}) {
            mvc.perform(post("/auth/wechat/"+endpoint).header("Host","luckyzll.site").header("Origin","https://evil.example")).andExpect(status().isForbidden());
            mvc.perform(post("/auth/wechat/"+endpoint).header("Host","luckyzll.site")).andExpect(status().isForbidden());
        }
        verifyNoInteractions(flows,identities,codes,captcha);
    }
    @Test void callbackRedirectContainsNoCodeOrTokens() throws Exception {
        mvc.perform(get("/auth/wechat/callback").cookie(cookie).param("code","secret-code").param("state","state"))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/login?wechat=complete"))
                .andExpect(header().string("Referrer-Policy","no-referrer"));
        verify(flows).callback(cookie.getValue(),"state","secret-code");
    }
    @Test void callbackFailureHasFixedSafeRedirect() throws Exception {
        doThrow(new IllegalArgumentException("secret-provider-response")).when(flows).callback(any(),any(),any());
        mvc.perform(get("/auth/wechat/callback").param("code","evil")).andExpect(redirectedUrl("/login?wechat=failed"));
    }
    @Test void firstScanRequiresEmailWithoutCreatingSession() throws Exception {
        when(flows.identity(cookie.getValue(),false)).thenReturn("open"); when(identities.user("open")).thenReturn(Optional.empty());
        mvc.perform(post("/auth/wechat/complete").cookie(cookie).header("Origin","https://luckyzll.site").header("Host","luckyzll.site"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requiresEmail").value(true)).andExpect(header().doesNotExist("Set-Cookie"));
        verify(flows,never()).identity(any(),eq(true)); verify(identities,never()).login(any(),any(),any());
    }
    @Test void legacyProofCannotBeUsedToRegisterAnotherEmailAccount() throws Exception {
        when(flows.identity(cookie.getValue(),false)).thenReturn("open");
        when(flows.legacyUser(cookie.getValue())).thenReturn(7L);
        mvc.perform(post("/auth/wechat/email/login").cookie(cookie).header("Origin","https://luckyzll.site").header("Host","luckyzll.site")
                .contentType("application/json").content("{\"email\":\"new@example.com\",\"code\":\"123456\"}"))
                .andExpect(status().isConflict());
        verifyNoInteractions(codes,identities);
    }
    @Test void legacyWechatBindingPreservesOriginalUserId() throws Exception {
        when(flows.identity(cookie.getValue(),false)).thenReturn("open");
        when(flows.identity(cookie.getValue(),true)).thenReturn("open");
        when(flows.legacyUser(cookie.getValue())).thenReturn(7L);
        when(migration.require("proof",false)).thenReturn(7L);
        when(migration.require("proof",true)).thenReturn(7L);
        when(migration.bindWechat(eq(7L),eq("open"),any(),any())).thenReturn(new AuthService.AuthenticatedSession(new AuthResponseVO(new UserVO(7L,"zhoulele","USER"),"access"),"refresh"));
        mvc.perform(post("/auth/wechat/complete").cookie(cookie,new Cookie(LegacyAccountMigration.COOKIE,"proof"))
                .header("Origin","https://luckyzll.site").header("Host","luckyzll.site"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.id").value(7));
        verifyNoInteractions(identities);
    }
    @Test void returningScanCreatesNormalSessionAndClearsFlowCookie() throws Exception {
        when(flows.identity(cookie.getValue(),false)).thenReturn("open"); when(flows.identity(cookie.getValue(),true)).thenReturn("open");
        when(identities.user("open")).thenReturn(Optional.of(7L));
        when(identities.login(eq("open"),any(),any())).thenReturn(new AuthService.AuthenticatedSession(new AuthResponseVO(new UserVO(7L,"zhoulele","USER"),"access"),"refresh"));
        mvc.perform(post("/auth/wechat/complete").cookie(cookie).header("Origin","https://luckyzll.site").header("Host","luckyzll.site"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.id").value(7))
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
        verify(flows).identity(cookie.getValue(),true);
    }
    @Test void emailProofIsFlowBoundAndConsumedBeforeLink() throws Exception {
        when(flows.identity(cookie.getValue(),false)).thenReturn("open"); when(flows.identity(cookie.getValue(),true)).thenReturn("open");
        when(codes.verify("old@example.com","123456","wechat-link:"+cookie.getValue())).thenReturn("old@example.com");
        when(identities.link(eq("open"),eq("old@example.com"),any(),any())).thenReturn(new AuthService.AuthenticatedSession(new AuthResponseVO(new UserVO(7L,"zhoulele","USER"),"access"),"refresh"));
        mvc.perform(post("/auth/wechat/email/login").cookie(cookie).header("Origin","https://luckyzll.site").header("Host","luckyzll.site")
                .contentType("application/json").content("{\"email\":\"old@example.com\",\"code\":\"123456\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.id").value(7));
        var order = inOrder(flows,codes,identities);
        order.verify(flows).identity(cookie.getValue(),false);
        order.verify(codes).verify("old@example.com","123456","wechat-link:"+cookie.getValue());
        order.verify(flows).identity(cookie.getValue(),true);
        order.verify(identities).link(eq("open"),eq("old@example.com"),any(),any());
    }
}
