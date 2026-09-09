package com.example.aiapp.service;

import com.example.aiapp.config.WechatLoginProperties;
import com.example.aiapp.mapper.UserMapper;
import com.example.aiapp.utils.PasswordUtils;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/** A restricted proof of legacy ownership, never a username/password login session. */
@Service
public class LegacyAccountMigration {
    public static final String COOKIE = "lyle_legacy_migration";
    private static final String PREFIX = "lyle:legacy-migration:";
    private final UserMapper users;
    private final PasswordEncoder passwords;
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final EmailIdentityService emails;
    private final AuthService auth;
    private final WechatLoginProperties wechat;
    public LegacyAccountMigration(UserMapper users, PasswordEncoder passwords, StringRedisTemplate redis, JdbcTemplate jdbc,
            EmailIdentityService emails, AuthService auth, WechatLoginProperties wechat) {
        this.users=users; this.passwords=passwords; this.redis=redis; this.jdbc=jdbc; this.emails=emails; this.auth=auth; this.wechat=wechat;
    }
    public String begin(String username, String password, String previous) {
        var user = users.selectByUsername(username);
        String hash = user == null ? null : user.getPasswordHash();
        boolean matches = hash != null && (hash.startsWith("sha256:") ? PasswordUtils.matches(password,hash) : passwords.matches(password,hash));
        if (user == null || !"ACTIVE".equalsIgnoreCase(user.getStatus()) || !matches)
            throw new ResponseStatusException(UNAUTHORIZED,"原用户名或密码不正确。");
        ensureUnbound(user.getId());
        cancel(previous);
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redis.opsForValue().set(PREFIX+ticket,user.getId()+":"+user.getAuthVersion(),Duration.ofMinutes(10));
        return ticket;
    }
    public Long require(String ticket, boolean consume) {
        if (ticket == null || !ticket.matches("[a-zA-Z0-9_-]{43}")) throw expired();
        String value = consume ? redis.opsForValue().getAndDelete(PREFIX+ticket) : redis.opsForValue().get(PREFIX+ticket);
        if (value == null) throw expired();
        String[] parts = value.split(":");
        var user = users.selectAccountById(Long.valueOf(parts[0]));
        if (user == null || !"ACTIVE".equalsIgnoreCase(user.getStatus()) || !String.valueOf(user.getAuthVersion()).equals(parts[1])) throw expired();
        ensureUnbound(user.getId());
        return user.getId();
    }
    public void cancel(String ticket) { if (ticket != null && ticket.matches("[a-zA-Z0-9_-]{43}")) redis.delete(PREFIX+ticket); }
    private void ensureUnbound(Long userId) {
        Long count = jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM auth_email_identity WHERE user_id = ?) + (SELECT COUNT(*) FROM auth_wechat_identity WHERE user_id = ?)",Long.class,userId,userId);
        if (count == null || count != 0) throw new ResponseStatusException(CONFLICT,"账号已绑定登录方式，请使用邮箱或微信登录。");
    }
    @Transactional
    public AuthService.AuthenticatedSession bindEmail(Long userId, String email, String agent, String address) {
        jdbc.queryForObject("SELECT id FROM app_user WHERE id = ? FOR UPDATE",Long.class,userId);
        ensureUnbound(userId);
        emails.bind(userId,email);
        return auth.loginVerifiedUser(userId,agent,address);
    }
    @Transactional
    public AuthService.AuthenticatedSession bindWechat(Long userId, String openId, String agent, String address) {
        jdbc.queryForObject("SELECT id FROM app_user WHERE id = ? FOR UPDATE",Long.class,userId);
        ensureUnbound(userId);
        jdbc.update("INSERT INTO auth_wechat_identity(app_id, open_id, user_id) VALUES (?, ?, ?)",wechat.appId(),openId,userId);
        return auth.loginVerifiedUser(userId,agent,address);
    }
    private static ResponseStatusException expired() { return new ResponseStatusException(BAD_REQUEST,"旧账号验证已过期，请重新验证原账号。"); }
}
