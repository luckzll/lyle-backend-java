package com.example.aiapp.security;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmailCodeServiceTest {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
    JavaMailSender mail = mock(JavaMailSender.class);
    EmailCodeService service;
    @BeforeEach void setup() {
        service = new EmailCodeService(redis, mail, true, "sender@example.com", "Lyle", "test-hmac-secret");
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), eq("1"), eq(Duration.ofSeconds(60)))).thenReturn(true);
        when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(1L);
        when(mail.createMimeMessage()).thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
    }
    @Test void normalizesAndRejectsHeaderInjection() {
        assertThat(EmailCodeService.normalize(" User@Example.COM ")).isEqualTo("user@example.com");
        assertThatThrownBy(() -> EmailCodeService.normalize("user@example.com\r\nBcc:attacker@example.com"))
                .isInstanceOf(ResponseStatusException.class);
    }
    @Test void storesDigestInsteadOfCodeAndUsesSameDigestOnVerification() throws Exception {
        service.send(" User@Example.COM ", "login");
        var digest = ArgumentCaptor.forClass(String.class);
        var key = ArgumentCaptor.forClass(String.class);
        verify(values).set(key.capture(), digest.capture(), eq(Duration.ofMinutes(5)));
        var message = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mail).send(message.capture());
        var matcher = Pattern.compile("[0-9]{6}").matcher(message.getValue().getContent().toString());
        assertThat(matcher.find()).isTrue();
        String code = matcher.group();
        assertThat(digest.getValue()).hasSize(64).isNotEqualTo(code);
        assertThat(service.verify("USER@example.com", code, "login")).isEqualTo("user@example.com");
        verify(redis).execute(eq(EmailCodeService.VERIFY), eq(List.of(key.getValue(), key.getValue() + ":attempts")), eq(digest.getValue()));
        service.verify("user@example.com", code, "bind:7");
        verify(redis).execute(eq(EmailCodeService.VERIFY), argThat(keys -> !keys.get(0).equals(key.getValue())), argThat((String hash) -> !hash.equals(digest.getValue())));
    }
    @Test void cooldownPreventsSending() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        assertThatThrownBy(() -> service.send("a@example.com", "login")).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(mail);
    }
    @Test void failedDeliveryInvalidatesCode() {
        doThrow(new MailSendException("smtp failure")).when(mail).send(any(MimeMessage.class));
        assertThatThrownBy(() -> service.send("a@example.com", "login")).isInstanceOf(ResponseStatusException.class);
        verify(redis).delete(startsWith("lyle:email:code:"));
    }
    @Test void invalidOrConsumedCodeCannotAuthenticate() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(0L);
        assertThatThrownBy(() -> service.verify("a@example.com", "123456", "login")).isInstanceOf(ResponseStatusException.class);
    }
    @Test void redisFailureFailsClosed() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenThrow(new IllegalStateException("offline"));
        assertThatThrownBy(() -> service.verify("a@example.com", "123456", "login"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("503");
    }
    @Test void disabledServiceDoesNotSend() {
        service = new EmailCodeService(redis, mail, false, "sender@example.com", "Lyle", "secret");
        assertThatThrownBy(() -> service.send("a@example.com", "login")).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(redis, mail);
    }
}
