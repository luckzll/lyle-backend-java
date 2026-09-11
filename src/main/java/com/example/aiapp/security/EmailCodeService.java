package com.example.aiapp.security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@Service
public class EmailCodeService {
    private static final Logger log = LoggerFactory.getLogger(EmailCodeService.class);

    private ResponseStatusException retryable(org.springframework.http.HttpStatus status, String message, String identity) {
        Long ttl = redis.getExpire("lyle:email:cooldown:" + identity);
        var headers = new org.springframework.http.HttpHeaders();
        if (ttl != null && ttl > 0) headers.set("Retry-After", Long.toString(ttl));
        return new ResponseStatusException(status, message) {
            @Override public org.springframework.http.HttpHeaders getHeaders() { return headers; }
        };
    }
    // Atomic rate limits survive restarts; no in-memory fallback for authentication.
    private static final DefaultRedisScript<Long> LIMIT = new DefaultRedisScript<>("""
        local n = redis.call('INCR', KEYS[1])
        if n == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
        return n
        """, Long.class);
    static final DefaultRedisScript<Long> VERIFY = new DefaultRedisScript<>("""
        local expected = redis.call('GET', KEYS[1])
        if not expected then return 0 end
        local n = redis.call('INCR', KEYS[2])
        if n == 1 then redis.call('EXPIRE', KEYS[2], 600) end
        if n > 5 then redis.call('DEL', KEYS[1]); return 0 end
        if expected ~= ARGV[1] then return 0 end
        redis.call('DEL', KEYS[1])
        return 1
        """, Long.class);
    private final StringRedisTemplate redis;
    private final JavaMailSender mail;
    private final boolean enabled;
    private final String from;
    private final String fromName;
    private final String secret;
    private final SecureRandom random = new SecureRandom();

    public EmailCodeService(StringRedisTemplate redis, JavaMailSender mail,
            @Value("${MAIL_ENABLED:false}") boolean enabled,
            @Value("${MAIL_FROM:}") String from,
            @Value("${MAIL_FROM_NAME:Lyle AI}") String fromName,
            @Value("${app.auth.jwt-secret}") String secret) {
        this.redis = redis; this.mail = mail; this.enabled = enabled;
        this.from = from; this.fromName = fromName; this.secret = secret;
    }

    public boolean enabled() { return enabled && !from.isBlank(); }

    public static String normalize(String email) {
        String value = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (value.length() > 254 || !value.matches("[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+")) {
            throw new ResponseStatusException(BAD_REQUEST, "请输入有效邮箱地址。");
        }
        return value;
    }

    private void ready() {
        if (!enabled()) throw new ResponseStatusException(SERVICE_UNAVAILABLE, "邮箱登录暂未启用。");
    }

    private String hash(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("Email code hashing unavailable"); }
    }

    public void send(String rawEmail, String purpose) {
        ready();
        String email = normalize(rawEmail);
        String identity = hash(email);
        String key = "lyle:email:code:" + hash(purpose + ":" + email);
        try {
            if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("lyle:email:cooldown:" + identity, "1", Duration.ofSeconds(60))))
                throw retryable(TOO_MANY_REQUESTS, "发送间隔过短，请等待倒计时结束后再发送。", identity);
            Long count = redis.execute(LIMIT, List.of("lyle:email:hour:" + identity), "3600");
            if (count == null || count > 10) throw new ResponseStatusException(TOO_MANY_REQUESTS, "发送次数过多，请稍后再试。");
            // Sending again does not reset the failed-attempt budget.
            String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
            redis.opsForValue().set(key, hash(purpose + ":" + email + ":" + code), Duration.ofMinutes(5));
            try {
                var message = mail.createMimeMessage();
                var helper = new MimeMessageHelper(message, true, "UTF-8");
                helper.setFrom(from, fromName);
                helper.setTo(email);
                helper.setSubject("Lyle AI 邮箱验证码");
                helper.setText("你的" + (purpose.startsWith("bind:") ? "绑定邮箱" : purpose.equals("password") ? "设置登录密码" : "登录") + "验证码是：" + code
                        + "\n5分钟内有效，仅可使用一次。请勿向他人提供验证码。如非本人操作，请忽略。",
                        VerificationEmail.html(code, purpose));
                mail.send(message);
            } catch (Exception e) {
                Throwable cause = e;
                while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                // Never log message bodies, codes, addresses or SMTP credentials.
                log.warn("Email delivery failed: exception={}, rootCause={}", e.getClass().getSimpleName(), cause.getClass().getSimpleName());
                redis.delete(key);
                throw retryable(SERVICE_UNAVAILABLE, "邮件发送失败，请稍后重试。", identity);
            }
        } catch (ResponseStatusException e) { throw e; }
        catch (RuntimeException e) { throw new ResponseStatusException(SERVICE_UNAVAILABLE, "邮箱验证服务暂不可用。"); }
    }

    public String verify(String rawEmail, String code, String purpose) {
        ready();
        String email = normalize(rawEmail);
        if (code == null || !code.matches("[0-9]{6}")) throw new ResponseStatusException(BAD_REQUEST, "请输入6位验证码。");
        String key = "lyle:email:code:" + hash(purpose + ":" + email);
        try {
            Long result = redis.execute(VERIFY, List.of(key, key + ":attempts"), hash(purpose + ":" + email + ":" + code));
            if (!Long.valueOf(1).equals(result)) throw new ResponseStatusException(BAD_REQUEST, "验证码错误、过期或尝试次数过多，请重新获取。");
            return email;
        } catch (ResponseStatusException e) { throw e; }
        catch (RuntimeException e) { throw new ResponseStatusException(SERVICE_UNAVAILABLE, "邮箱验证服务暂不可用。"); }
    }
}
