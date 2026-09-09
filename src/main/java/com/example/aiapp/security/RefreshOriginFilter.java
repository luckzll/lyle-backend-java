package com.example.aiapp.security;

import com.example.aiapp.config.AuthProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public class RefreshOriginFilter extends OncePerRequestFilter {

    private final AuthProperties properties;

    public RefreshOriginFilter(AuthProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String requestPath = request.getRequestURI().substring(request.getContextPath().length());
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !("/auth/refresh".equals(requestPath) || requestPath.startsWith("/auth/wechat/") || requestPath.startsWith("/auth/migration/"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String browserOrigin = browserOrigin(request);
        if (browserOrigin == null
                || !allowedOrigins().contains(browserOrigin)
                || !matchesRequestHost(browserOrigin, request.getHeader(HttpHeaders.HOST))) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Refresh origin is not allowed.");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private String browserOrigin(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && !origin.isBlank()) {
            return normalizeOrigin(origin);
        }
        String referer = request.getHeader(HttpHeaders.REFERER);
        return referer == null || referer.isBlank() ? null : normalizeOrigin(referer);
    }

    private Set<String> allowedOrigins() {
        String configured = properties.allowedOrigins();
        if (configured == null || configured.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(this::normalizeOrigin)
                .filter(value -> value != null)
                .collect(Collectors.toUnmodifiableSet());
    }

    private boolean matchesRequestHost(String origin, String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return false;
        }
        try {
            String originHost = UriComponentsBuilder.fromUriString(origin).build().getHost();
            String requestHost = UriComponentsBuilder
                    .fromUriString("http://" + hostHeader)
                    .build()
                    .getHost();
            return originHost != null && originHost.equalsIgnoreCase(requestHost);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private String normalizeOrigin(String value) {
        try {
            UriComponents uri = UriComponentsBuilder.fromUriString(value).build();
            if (uri.getScheme() == null
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                return null;
            }
            int port = uri.getPort();
            boolean defaultPort = port == -1
                    || ("https".equalsIgnoreCase(uri.getScheme()) && port == 443)
                    || ("http".equalsIgnoreCase(uri.getScheme()) && port == 80);
            return uri.getScheme().toLowerCase()
                    + "://"
                    + uri.getHost().toLowerCase()
                    + (defaultPort ? "" : ":" + port);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
