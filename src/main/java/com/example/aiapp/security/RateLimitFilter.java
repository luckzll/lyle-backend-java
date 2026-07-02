package com.example.aiapp.security;

import com.example.aiapp.config.RateLimitProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitProperties properties;
    private final FixedWindowRateLimiter limiter = new FixedWindowRateLimiter();

    public RateLimitFilter(RateLimitProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String path = request.getRequestURI();
        int limit = limitFor(path);
        if (limit > 0 && !limiter.allow(key(request, path), limit)) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"message\":\"请求过于频繁，请稍后再试\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private int limitFor(String path) {
        if (path.endsWith("/auth/login")) {
            return properties.authPerMinute();
        }
        if (path.endsWith("/chat/send") || path.endsWith("/chat/stream")) {
            return properties.chatPerMinute();
        }
        return 0;
    }

    private String key(HttpServletRequest request, String path) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = forwarded == null || forwarded.isBlank()
                ? request.getRemoteAddr()
                : forwarded.split(",")[0].trim();
        return path + ":" + ip;
    }
}
