package com.example.aiapp.security;

import com.example.aiapp.config.WechatLoginProperties;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@Service
public class WechatLoginFlow {
    public static final int TTL_SECONDS = 300;
    private static final String PREFIX = "lyle:wechat-login:";
    private static final DefaultRedisScript<String> CONSUME_STATE = new DefaultRedisScript<>("""
        local value = redis.call('GET', KEYS[1])
        if not value or string.sub(value, 1, 43) ~= ARGV[1] then return '' end
        redis.call('DEL', KEYS[1])
        return value
        """, String.class);
    private final StringRedisTemplate redis;
    private final WechatOAuthClient client;
    private final WechatLoginProperties properties;
    private final SecureRandom random = new SecureRandom();
    public WechatLoginFlow(StringRedisTemplate redis, WechatOAuthClient client, WechatLoginProperties properties) {
        this.redis = redis; this.client = client; this.properties = properties;
    }
    public record Start(String browserToken, String qrUrl, int expiresInSeconds) {}
    public Start start(String previous) {
        return start(previous, null);
    }
    public Start start(String previous, Long legacyUser) {
        ready(); cancel(previous);
        String browser = token(), state = token();
        redis.opsForValue().set(PREFIX + "state:" + browser, state + "|" + (legacyUser == null ? "0" : legacyUser), Duration.ofSeconds(TTL_SECONDS));
        // Matches the official wxLogin.js embed contract. Redirects the top-level browser,
        // so the Lax HttpOnly browser-binding cookie is available at the callback.
        String url = "https://open.weixin.qq.com/connect/qrconnect?appid=" + properties.appId()
                + "&scope=snsapi_login&redirect_uri=" + WechatOAuthClient.enc(properties.callbackUrl())
                + "&state=" + state + "&login_type=jssdk&self_redirect=false&style=black";
        return new Start(browser, url, TTL_SECONDS);
    }
    public void callback(String browser, String state, String code) {
        ready(); valid(browser); valid(state);
        if (code == null || !code.matches("[a-zA-Z0-9_-]{1,256}")) throw expired();
        String saved = redis.execute(CONSUME_STATE, List.of(PREFIX + "state:" + browser), state);
        if (saved == null || saved.isBlank()) throw expired();
        String legacyId = saved.substring(44);
        String openId = client.exchange(code);
        redis.opsForValue().set(PREFIX + "identity:" + browser, properties.appId() + ":" + openId + ":" + legacyId,
                Duration.ofSeconds(TTL_SECONDS));
    }
    public String identity(String browser, boolean consume) {
        ready(); valid(browser);
        String key = PREFIX + "identity:" + browser;
        String value = consume ? redis.opsForValue().getAndDelete(key) : redis.opsForValue().get(key);
        // A deployment changing AppID invalidates any outstanding authorization.
        String prefix = properties.appId() + ":";
        if (value == null || !value.startsWith(prefix)) throw expired();
        return value.substring(prefix.length()).split(":")[0];
    }
    public void cancel(String browser) {
        if (browser != null && browser.matches("[a-zA-Z0-9_-]{43}"))
            redis.delete(List.of(PREFIX + "state:" + browser, PREFIX + "identity:" + browser));
    }
    public Long legacyUser(String browser) {
        valid(browser);
        String value = redis.opsForValue().get(PREFIX + "identity:" + browser);
        if (value == null) throw expired();
        String id = value.split(":")[2];
        return "0".equals(id) ? null : Long.valueOf(id);
    }
    private String token() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private void valid(String token) { if (token == null || !token.matches("[a-zA-Z0-9_-]{43}")) throw expired(); }
    private void ready() { if (!properties.configured()) throw new ResponseStatusException(SERVICE_UNAVAILABLE, "微信登录暂未开放，请使用邮箱登录。"); }
    private static ResponseStatusException expired() { return new ResponseStatusException(BAD_REQUEST, "微信授权已失效，请重新扫码。"); }
}
