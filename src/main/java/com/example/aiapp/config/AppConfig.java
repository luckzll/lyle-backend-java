package com.example.aiapp.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        int maxInMemorySize = properties.maxInMemorySize() == null
                ? 4 * 1024 * 1024
                : properties.maxInMemorySize();
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(properties.baseUrl())
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(maxInMemorySize));
        if (properties.internalToken() != null && !properties.internalToken().isBlank()) {
            builder.defaultHeader("X-Internal-Token", properties.internalToken());
        }
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
