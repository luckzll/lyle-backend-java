package com.example.aiapp.service;

import com.example.aiapp.domain.dto.AuthRequestDTO;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Service
public class EmailIdentityService {
    private final JdbcTemplate jdbc;
    private final AuthService auth;
    private final PasswordEncoder passwordEncoder;
    private final String unavailablePasswordHash;
    public EmailIdentityService(JdbcTemplate jdbc, AuthService auth, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc; this.auth = auth; this.passwordEncoder = passwordEncoder;
        this.unavailablePasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AuthService.AuthenticatedSession login(String verifiedEmail, String agent, String address) {
        var ids = jdbc.queryForList("SELECT user_id FROM auth_email_identity WHERE email = ?", Long.class, verifiedEmail);
        if (!ids.isEmpty()) {
            lockAccount(ids.get(0));
            return auth.loginVerifiedUser(ids.get(0), agent, address);
        }
        // Do not infer ownership from a username resembling an email or the SMTP sender.
        var session = auth.register(new AuthRequestDTO("email_" + UUID.randomUUID().toString().replace("-", ""),
                UUID.randomUUID().toString() + UUID.randomUUID()), agent, address);
        jdbc.update("INSERT INTO auth_email_identity(email, user_id, verified_at) VALUES (?, ?, CURRENT_TIMESTAMP)",
                verifiedEmail, session.response().user().id());
        return session;
    }

    public String email(Long userId) {
        var rows = jdbc.queryForList("SELECT email FROM auth_email_identity WHERE user_id = ?", String.class, userId);
        return rows.isEmpty() ? "" : rows.get(0);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AuthService.AuthenticatedSession loginWithPassword(String email, String password, String agent, String address) {
        // Only a verified binding establishes the email-to-account relationship.
        var usernames = jdbc.queryForList("SELECT u.username FROM auth_email_identity e JOIN app_user u ON u.id = e.user_id WHERE e.email = ?",
                String.class, email);
        if (usernames.isEmpty()) {
            passwordEncoder.matches(password, unavailablePasswordHash);
            throw new ResponseStatusException(UNAUTHORIZED, "邮箱或密码不正确，请重试。");
        }
        // Serialize password checks and session creation with verified password resets.
        // READ_COMMITTED makes the subsequent account read see the latest hash/version
        // after waiting for this lock, even if a reset committed after identity lookup.
        jdbc.queryForObject("SELECT id FROM app_user WHERE username = ? FOR UPDATE", Long.class, usernames.get(0));
        return auth.login(new AuthRequestDTO(usernames.get(0), password), agent, address);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AuthService.AuthenticatedSession resetPassword(String verifiedEmail, String password, String agent, String address) {
        var ids = jdbc.queryForList("SELECT user_id FROM auth_email_identity WHERE email = ?", Long.class, verifiedEmail);
        if (ids.isEmpty()) throw new ResponseStatusException(BAD_REQUEST, "此邮箱尚未注册，请先使用邮箱验证码登录，再设置密码。");
        Long userId = ids.get(0);
        lockAccount(userId);
        return auth.resetVerifiedPassword(userId, password, agent, address);
    }

    private void lockAccount(Long userId) {
        jdbc.queryForObject("SELECT id FROM app_user WHERE id = ? FOR UPDATE", Long.class, userId);
    }

    @Transactional
    public void bind(Long userId, String verifiedEmail) {
        jdbc.queryForObject("SELECT id FROM app_user WHERE id = ? FOR UPDATE", Long.class, userId);
        String existing = email(userId);
        if (existing.equals(verifiedEmail)) return;
        if (!existing.isEmpty()) throw new ResponseStatusException(CONFLICT, "当前账号已绑定邮箱。");
        jdbc.update("INSERT INTO auth_email_identity(email, user_id, verified_at) VALUES (?, ?, CURRENT_TIMESTAMP)", verifiedEmail, userId);
    }
}
