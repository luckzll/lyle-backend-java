package com.example.aiapp.config;

import io.netty.channel.ChannelOption;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties({
        AiServiceProperties.class,
        AuthProperties.class,
        RedisProperties.class,
        MinioProperties.class,
        QdrantProperties.class,
        RateLimitProperties.class,
        TosProperties.class
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
}
