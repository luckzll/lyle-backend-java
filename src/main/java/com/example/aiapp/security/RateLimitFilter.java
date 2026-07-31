package com.example.aiapp.security;

import com.example.aiapp.config.RateLimitProperties;
import com.example.aiapp.config.RedisProperties;
import com.example.aiapp.config.AuthProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final int WINDOW_MILLIS = 60_000;
    private static final int MAX_AUTH_BODY_BYTES = 16 * 1024;
    private static final DefaultRedisScript<Long> SLIDING_WINDOW_SCRIPT = new DefaultRedisScript<>(
            """
            redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[1] - ARGV[2])
            local count = redis.call('ZCARD', KEYS[1])
            if count >= tonumber(ARGV[3]) then
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
              return 0
            end
            redis.call('ZADD', KEYS[1], ARGV[1], ARGV[4])
            redis.call('PEXPIRE', KEYS[1], ARGV[2])
            return 1
            """,
            Long.class
    );

    private final RateLimitProperties properties;
    private final RedisProperties redisProperties;
    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final ObjectMapper objectMapper;
    private final AuthProperties authProperties;
    private final FixedWindowRateLimiter fallbackLimiter = new FixedWindowRateLimiter();
    private final ClientIpResolver clientIpResolver;
    private final AtomicLong lastRedisWarningAt = new AtomicLong();

    public RateLimitFilter(
            RateLimitProperties properties,
            RedisProperties redisProperties,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            ObjectMapper objectMapper,
            AuthProperties authProperties
    ) {
        this.properties = properties;
        this.redisProperties = redisProperties;
        this.redisTemplateProvider = redisTemplateProvider;
        this.objectMapper = objectMapper;
        this.authProperties = authProperties;
        this.clientIpResolver = new ClientIpResolver(properties.trustedProxyCidrs());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        HttpServletRequest effectiveRequest = requiresAuthBody(request)
                ? new BufferedRequest(request)
                : request;
        for (LimitKey limitKey : keys(effectiveRequest)) {
            if (!allow(limitKey.key(), limitKey.limit())) {
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"message\":\"请求过于频繁，请稍后再试\"}");
                return;
            }
        }
        filterChain.doFilter(effectiveRequest, response);
    }

    private List<LimitKey> keys(HttpServletRequest request) {
        String path = request.getRequestURI();
        String ip = digest(clientIpResolver.resolve(request));
        List<LimitKey> keys = new ArrayList<>();
        if (path.endsWith("/auth/login")) {
            keys.add(new LimitKey("auth:login:ip:" + ip, positive(properties.authPerMinute(), 20)));
            String username = username(request);
            if (!username.isBlank()) {
                keys.add(new LimitKey(
                        "auth:login:username:" + digest(username.toLowerCase(Locale.ROOT)),
                        positive(properties.loginUsernamePerMinute(), 10)
                ));
            }
        } else if (path.endsWith("/auth/register")) {
            keys.add(new LimitKey(
                    "auth:register:" + ip + ":" + digest(defaultString(request.getHeader("User-Agent"), "unknown")),
                    positive(properties.registerPerMinute(), 8)
            ));
        } else if (path.endsWith("/auth/refresh")) {
            keys.add(new LimitKey(
                    "auth:refresh:" + ip + ":" + digest(refreshCookie(request)),
                    positive(properties.refreshPerMinute(), 30)
            ));
        } else if (path.endsWith("/auth/change-password")) {
            keys.add(new LimitKey(
                    "auth:password:" + ip + ":" + digest(defaultString(request.getHeader("Authorization"), "anonymous")),
                    positive(properties.passwordPerMinute(), 6)
            ));
        } else if (path.endsWith("/experience/public-events")) {
            keys.add(new LimitKey("public-event:" + ip, positive(properties.authPerMinute(), 20)));
        } else if (path.endsWith("/chat/send") || path.endsWith("/chat/stream")) {
            keys.add(new LimitKey("chat:" + ip, positive(properties.chatPerMinute(), 60)));
        }
        return keys;
    }

    private boolean allow(String key, int limit) {
        if (redisProperties.enabled()) {
            StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
            if (redisTemplate != null) {
                try {
                    long now = System.currentTimeMillis();
                    Long allowed = redisTemplate.execute(
                            SLIDING_WINDOW_SCRIPT,
                            List.of("lyle:rate-limit:" + key),
                            String.valueOf(now),
                            String.valueOf(WINDOW_MILLIS),
                            String.valueOf(limit),
                            now + ":" + UUID.randomUUID()
                    );
                    return Long.valueOf(1L).equals(allowed);
                } catch (RuntimeException exception) {
                    warnRedisFallback(exception);
                }
            }
        }
        return fallbackLimiter.allow(key, limit);
    }

    private void warnRedisFallback(RuntimeException exception) {
        long now = System.currentTimeMillis();
        long last = lastRedisWarningAt.get();
        if (now - last > Duration.ofMinutes(1).toMillis() && lastRedisWarningAt.compareAndSet(last, now)) {
            log.warn("Redis rate limiter unavailable; using conservative local fallback.", exception);
        }
    }

    private String username(HttpServletRequest request) {
        if (!(request instanceof BufferedRequest bufferedRequest)) {
            return "";
        }
        try {
            JsonNode body = objectMapper.readTree(bufferedRequest.body());
            return body == null ? "" : body.path("username").asText("").trim();
        } catch (IOException exception) {
            return "";
        }
    }

    private static boolean requiresAuthBody(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "POST".equalsIgnoreCase(request.getMethod())
                && (path.endsWith("/auth/login") || path.endsWith("/auth/register"));
    }

    private String refreshCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return "missing";
        }
        for (Cookie cookie : cookies) {
            if (authProperties.refreshCookieName().equals(cookie.getName())) {
                return defaultString(cookie.getValue(), "missing");
            }
        }
        return "missing";
    }

    private static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private static String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record LimitKey(String key, int limit) {
    }

    private static final class BufferedRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private BufferedRequest(HttpServletRequest request) throws IOException, ServletException {
            super(request);
            byte[] content = request.getInputStream().readNBytes(MAX_AUTH_BODY_BYTES + 1);
            if (content.length > MAX_AUTH_BODY_BYTES) {
                throw new ServletException("Authentication request body is too large");
            }
            this.body = content;
        }

        byte[] body() {
            return body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    // Synchronous MVC consumes authentication requests.
                }

                @Override
                public int read() {
                    return input.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
