package com.example.aiapp.security;

import com.example.aiapp.config.WechatLoginProperties;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class WechatLoginFlowTest {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked") ValueOperations<String,String> values = mock(ValueOperations.class);
    WechatOAuthClient client = mock(WechatOAuthClient.class);
    WechatLoginProperties props = new WechatLoginProperties(true, "wx1234567890123456", "a".repeat(32), "https://luckyzll.site/api/auth/wechat/callback");
    Map<String,String> store = new HashMap<>();
    WechatLoginFlow flow;
    @BeforeEach void setup() {
        when(redis.opsForValue()).thenReturn(values);
        doAnswer(a -> { store.put(a.getArgument(0), a.getArgument(1)); return null; }).when(values).set(anyString(), anyString(), any(Duration.class));
        when(values.get(anyString())).thenAnswer(a -> store.get(a.getArgument(0)));
        when(values.getAndDelete(anyString())).thenAnswer(a -> store.remove(a.getArgument(0)));
        when(redis.delete(anyCollection())).thenAnswer(a -> { ((Collection<String>)a.getArgument(0)).forEach(store::remove); return 1L; });
        when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenAnswer(a -> {
            String key = ((List<String>)a.getArgument(1)).get(0);
            String saved = store.get(key);
            if (saved == null || !saved.substring(0,43).equals(a.getArgument(2))) return "";
            store.remove(key); return saved;
        });
        when(client.exchange("good-code")).thenReturn("openid_one");
        flow = new WechatLoginFlow(redis, client, props);
    }
    String state(WechatLoginFlow.Start start) { return org.springframework.web.util.UriComponentsBuilder.fromUriString(start.qrUrl()).build().getQueryParams().getFirst("state"); }
    @Test void configuredRequiresCompleteHttpsWebsiteCredentials() {
        assertThat(props.configured()).isTrue();
        assertThat(props.toString()).doesNotContain("a".repeat(32));
        assertThat(new WechatLoginProperties(false, props.appId(), props.appSecret(), props.callbackUrl()).configured()).isFalse();
        assertThat(new WechatLoginProperties(true, props.appId(), "网站应用AppSecret", props.callbackUrl()).configured()).isFalse();
        assertThat(new WechatLoginProperties(true, props.appId(), props.appSecret(), "http://luckyzll.site/api/auth/wechat/callback").configured()).isFalse();
    }
    @Test void startHasRandomIndependentBrowserAndStateAndNeverSecret() {
        var first = flow.start(null); var second = flow.start(null);
        assertThat(first.browserToken()).hasSize(43).isNotEqualTo(state(first)).isNotEqualTo(second.browserToken());
        assertThat(first.qrUrl()).contains("scope=snsapi_login", "self_redirect=false").doesNotContain(props.appSecret(), first.browserToken());
        verify(values, times(2)).set(anyString(), anyString(), eq(Duration.ofSeconds(300)));
    }
    @Test void validCallbackAndIdentityAreSingleUse() {
        var start = flow.start(null); flow.callback(start.browserToken(), state(start), "good-code");
        assertThat(flow.identity(start.browserToken(), false)).isEqualTo("openid_one");
        assertThatThrownBy(() -> flow.callback(start.browserToken(), state(start), "good-code")).hasMessageContaining("失效");
        assertThat(flow.identity(start.browserToken(), true)).isEqualTo("openid_one");
        assertThatThrownBy(() -> flow.identity(start.browserToken(), true)).hasMessageContaining("失效");
        verify(client, times(1)).exchange("good-code");
    }
    @Test void wrongBrowserOrStateCannotAuthorizeOrConsumeLegitimateFlow() {
        var start = flow.start(null);
        assertThatThrownBy(() -> flow.callback("x".repeat(43), state(start), "good-code")).hasMessageContaining("失效");
        assertThatThrownBy(() -> flow.callback(start.browserToken(), "x".repeat(43), "good-code")).hasMessageContaining("失效");
        verifyNoInteractions(client);
        flow.callback(start.browserToken(), state(start), "good-code");
    }
    @Test void refreshCancelsPreviousAndExpiredMissingStateFailsClosed() {
        var old = flow.start(null); flow.start(old.browserToken());
        assertThatThrownBy(() -> flow.callback(old.browserToken(), state(old), "good-code")).hasMessageContaining("失效");
        assertThatThrownBy(() -> flow.callback(null, state(old), "good-code")).hasMessageContaining("失效");
        assertThatThrownBy(() -> flow.identity(old.browserToken(), false)).hasMessageContaining("失效");
        verifyNoInteractions(client);
    }
    @Test void providerFailureCannotLeaveAuthorizedIdentity() {
        var start = flow.start(null);
        when(client.exchange("bad-code")).thenThrow(new IllegalStateException("provider failure"));
        assertThatThrownBy(() -> flow.callback(start.browserToken(), state(start), "bad-code")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> flow.identity(start.browserToken(), false)).hasMessageContaining("失效");
    }
    @Test void configurationChangeInvalidatesPendingIdentity() {
        var start = flow.start(null); flow.callback(start.browserToken(), state(start), "good-code");
        var changed = new WechatLoginFlow(redis, client, new WechatLoginProperties(true,"wx6543210987654321",props.appSecret(),props.callbackUrl()));
        assertThatThrownBy(() -> changed.identity(start.browserToken(), false)).hasMessageContaining("失效");
    }
    @Test void disabledDoesNotContactRedisOrProvider() {
        var disabled = new WechatLoginFlow(redis, client, new WechatLoginProperties(false,null,null,null));
        assertThatThrownBy(() -> disabled.start(null)).hasMessageContaining("暂未开放");
        verifyNoInteractions(client); verify(values, never()).set(anyString(),anyString(),any(Duration.class));
    }
    @Test void oauthResponseRequiresLoginScopeAndNoError() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(WechatOAuthClient.parseOpenId(json.readTree("{\"openid\":\"open\",\"access_token\":\"token\",\"scope\":\"snsapi_login\"}"))).isEqualTo("open");
        for (String body : List.of("{}", "{\"errcode\":40029,\"errmsg\":\"private details\"}", "{\"openid\":\"open\",\"access_token\":\"token\",\"scope\":\"snsapi_base\"}")) {
            assertThatThrownBy(() -> WechatOAuthClient.parseOpenId(json.readTree(body))).hasMessageContaining("微信授权失败").hasMessageNotContaining("private details");
        }
    }
}
