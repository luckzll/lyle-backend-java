package com.example.aiapp.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;

class MailProxyConfigurationTest {
    @Test
    void productionProxyUsesImplicitTlsOnPort465() {
        try (var context = new SpringApplicationBuilder(MailConfiguration.class)
                .web(WebApplicationType.NONE)
                .run("--spring.profiles.active=production,mail-proxy",
                        "--MAIL_PROXY_HOST=lyle-smtp-proxy",
                        "--SPRING_MAIL_PORT=465",
                        "--SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=true")) {
            var mail = context.getBean(MailProperties.class);
            assertThat(mail.getPort()).isEqualTo(465);
            assertThat(mail.getProperties()).containsEntry("mail.smtp.ssl.enable", "true")
                    .containsEntry("mail.smtp.starttls.enable", "false")
                    .containsEntry("mail.smtp.starttls.required", "false");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MailProperties.class)
    static class MailConfiguration {}
}
