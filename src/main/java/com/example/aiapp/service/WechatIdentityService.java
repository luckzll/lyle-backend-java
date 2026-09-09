package com.example.aiapp.service;

import com.example.aiapp.config.WechatLoginProperties;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.CONFLICT;

@Service
public class WechatIdentityService {
    private final JdbcTemplate jdbc;
    private final AuthService auth;
    private final EmailIdentityService email;
    private final WechatLoginProperties properties;
    public WechatIdentityService(JdbcTemplate jdbc, AuthService auth, EmailIdentityService email, WechatLoginProperties properties) {
        this.jdbc = jdbc; this.auth = auth; this.email = email; this.properties = properties;
    }
    public Optional<Long> user(String openId) {
        return jdbc.queryForList("SELECT user_id FROM auth_wechat_identity WHERE app_id = ? AND open_id = ?",
                Long.class, properties.appId(), openId).stream().findFirst();
    }
    @Transactional
    public AuthService.AuthenticatedSession login(String openId, String agent, String address) {
        return auth.loginVerifiedUser(user(openId).orElseThrow(() -> new ResponseStatusException(CONFLICT, "请先验证邮箱。")), agent, address);
    }
    @Transactional
    public AuthService.AuthenticatedSession link(String openId, String verifiedEmail, String agent, String address) {
        var existing = user(openId);
        if (existing.isPresent()) {
            if (!email.email(existing.get()).equals(verifiedEmail))
                throw new ResponseStatusException(CONFLICT, "该微信已关联其他账号，请重新扫码登录。");
            return auth.loginVerifiedUser(existing.get(), agent, address);
        }
        // Email possession is proved before entering this transaction. Preserve the original
        // account ID, data, role and entitlements; never merge by nickname/UnionID heuristics.
        var session = email.login(verifiedEmail, agent, address);
        jdbc.update("INSERT INTO auth_wechat_identity(app_id, open_id, user_id) VALUES (?, ?, ?)",
                properties.appId(), openId, session.response().user().id());
        return session;
    }
}
