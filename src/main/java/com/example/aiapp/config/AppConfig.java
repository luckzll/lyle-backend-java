package com.example.aiapp.config;

import io.netty.channel.ChannelOption;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
@EnableConfigurationProperties({
        AiServiceProperties.class,
        AuthProperties.class,
        WechatLoginProperties.class,
        RedisProperties.class,
        MinioProperties.class,
        QdrantProperties.class,
        RateLimitProperties.class,
        TosProperties.class,
        V63FeatureProperties.class,
        UsageProperties.class
})
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    @Bean
    WebClient aiServiceWebClient(AiServiceProperties properties) {
        if (!StringUtils.hasText(properties.internalToken())) {
            throw new IllegalStateException(
                    "AI_SERVICE_INTERNAL_TOKEN must be configured with the same strong token as Python INTERNAL_API_TOKEN."
            );
        }
        int maxInMemorySize = properties.maxInMemorySize() == null
                ? 4 * 1024 * 1024
                : properties.maxInMemorySize();
        int connectTimeoutMillis = properties.connectTimeoutMillis() == null
                ? 5_000
                : properties.connectTimeoutMillis();
        int responseTimeoutSeconds = properties.responseTimeoutSeconds() == null
                ? 120
                : properties.responseTimeoutSeconds();
        // responseTimeout 基于读空闲：AI 服务挂起或断流超过该时长即失败，避免占死 Servlet 线程；
        // 正常持续推送的 SSE 流不受影响。
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis)
                .responseTimeout(Duration.ofSeconds(responseTimeoutSeconds));
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(properties.baseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(maxInMemorySize));
        builder.defaultHeader("X-Internal-Token", properties.internalToken());
        return builder.defaultHeader(HttpHeaders.USER_AGENT, "ai-app-backend").build();
    }

    @Bean(name = "chatGenerationExecutor", destroyMethod = "shutdown")
    Executor chatGenerationExecutor() {
        AtomicInteger threadSequence = new AtomicInteger();
        return new ThreadPoolExecutor(
                8,
                64,
                60,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "chat-generation-" + threadSequence.incrementAndGet()
                    );
                    thread.setDaemon(false);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @Bean
    ApplicationRunner tosConfigLogger(TosProperties properties) {
        return args -> log.info(
                "TOS config loaded. enabled={} endpoint={} bucket={} savePathHead={} akConfigured={} skConfigured={}",
                properties.enabled(),
                properties.endpoint(),
                properties.bucket(),
                properties.savePathHead(),
                StringUtils.hasText(properties.accessKeyId()),
                StringUtils.hasText(properties.secretAccessKey())
        );
    }

    @Bean
    ApplicationRunner productionSecurityValidator(
            Environment environment,
            AuthProperties authProperties,
            UsageProperties usageProperties,
            AiServiceProperties aiServiceProperties
    ) {
        return args -> {
            if (environment.acceptsProfiles(Profiles.of("prod", "production"))) {
                validateProductionSecurity(authProperties, usageProperties, aiServiceProperties);
            }
        };
    }

    void validateProductionSecurity(
            AuthProperties authProperties,
            UsageProperties usageProperties,
            AiServiceProperties aiServiceProperties
    ) {
        if (weakSecret(authProperties.jwtSecret())) {
            throw new IllegalStateException("Production JWT_SECRET must be a strong random secret.");
        }
        if (!StringUtils.hasText(authProperties.jwtKeyId())) {
            throw new IllegalStateException("Production JWT_KEY_ID must be configured.");
        }
        if (!authProperties.secureCookie()) {
            throw new IllegalStateException("REFRESH_COOKIE_SECURE must be true in production.");
        }
        if (authProperties.acceptLegacyJwt()) {
            throw new IllegalStateException("AUTH_ACCEPT_LEGACY_JWT must be false in production.");
        }
        if (weakSecret(usageProperties.internalToken())) {
            throw new IllegalStateException("AI_USAGE_INTERNAL_TOKEN must be a strong random token.");
        }
        if (weakSecret(aiServiceProperties.internalToken())) {
            throw new IllegalStateException("AI_SERVICE_INTERNAL_TOKEN must be a strong random token.");
        }
        if (!StringUtils.hasText(authProperties.allowedOrigins())
                || authProperties.allowedOrigins().contains("*")
                || authProperties.allowedOrigins().contains("localhost")
                || authProperties.allowedOrigins().contains("127.0.0.1")
                || Arrays.stream(authProperties.allowedOrigins().split(","))
                .map(String::trim)
                .anyMatch(origin -> !origin.startsWith("https://"))) {
            throw new IllegalStateException(
                    "AUTH_ALLOWED_ORIGINS must contain explicit HTTPS production origins."
            );
        }
        boolean previousSecretConfigured = StringUtils.hasText(authProperties.jwtPreviousSecret());
        boolean previousKeyIdConfigured = StringUtils.hasText(authProperties.jwtPreviousKeyId());
        if (previousSecretConfigured != previousKeyIdConfigured) {
            throw new IllegalStateException(
                    "JWT_PREVIOUS_SECRET and JWT_PREVIOUS_KEY_ID must be configured together."
            );
        }
    }

    private static boolean weakSecret(String value) {
        if (!StringUtils.hasText(value) || value.length() < 32) {
            return true;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("change-me")
                || normalized.contains("change-this")
                || normalized.contains("default")
                || normalized.contains("example")
                || normalized.contains("lyle-dev");
    }
}
