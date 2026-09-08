package com.example.aiapp.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;

/** No remote images or recipient-provided HTML; code remains selectable plain text. */
final class VerificationEmail {
    private static final String TEMPLATE = load();
    private VerificationEmail() {}
    private static String load() {
        try (var input = new ClassPathResource("mail/verification-code.html").getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new IllegalStateException("Verification email template unavailable", e); }
    }
    static String html(String code, boolean binding) {
        if (!code.matches("[0-9]{6}")) throw new IllegalArgumentException("Invalid verification code");
        return TEMPLATE.replace("{{code}}", code)
                .replace("{{action}}", binding ? "绑定邮箱" : "登录")
                .replace("{{title}}", binding ? "验证你的邮箱" : "欢迎回来")
                .replace("{{description}}", binding ? "请在绑定邮箱页面输入下方验证码，完成邮箱绑定。" : "请在登录页面输入下方验证码，继续你的 AI 工作之旅。");
    }
}
