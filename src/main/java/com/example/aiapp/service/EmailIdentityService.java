package com.example.aiapp.service;

import com.example.aiapp.domain.dto.AuthRequestDTO;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.CONFLICT;

@Service
public class EmailIdentityService {
    private final JdbcTemplate jdbc;
    private final AuthService auth;
    public EmailIdentityService(JdbcTemplate jdbc, AuthService auth) { this.jdbc = jdbc; this.auth = auth; }

    @Transactional
    public AuthService.AuthenticatedSession login(String verifiedEmail, String agent, String address) {
        var ids = jdbc.queryForList("SELECT user_id FROM auth_email_identity WHERE email = ?", Long.class, verifiedEmail);
        if (!ids.isEmpty()) return auth.loginVerifiedUser(ids.get(0), agent, address);
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

    @Transactional
    public void bind(Long userId, String verifiedEmail) {
        jdbc.queryForObject("SELECT id FROM app_user WHERE id = ? FOR UPDATE", Long.class, userId);
        String existing = email(userId);
        if (existing.equals(verifiedEmail)) return;
        if (!existing.isEmpty()) throw new ResponseStatusException(CONFLICT, "当前账号已绑定邮箱。");
        jdbc.update("INSERT INTO auth_email_identity(email, user_id, verified_at) VALUES (?, ?, CURRENT_TIMESTAMP)", verifiedEmail, userId);
    }
}
