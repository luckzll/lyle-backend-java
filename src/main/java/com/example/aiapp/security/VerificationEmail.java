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
        return html(code, binding ? "bind:" : "login");
    }
    static String html(String code, String purpose) {
        if (!code.matches("[0-9]{6}")) throw new IllegalArgumentException("Invalid verification code");
        boolean binding = purpose.startsWith("bind:");
        boolean password = purpose.equals("password");
        return TEMPLATE.replace("{{code}}", code)
                .replace("{{action}}", binding ? "绑定邮箱" : password ? "设置登录密码" : "登录")
                .replace("{{title}}", binding ? "验证你的邮箱" : password ? "设置你的登录密码" : "欢迎回来")
                .replace("{{description}}", binding ? "请在绑定邮箱页面输入下方验证码，完成邮箱绑定。" : password
                        ? "请在设置密码页面输入下方验证码。完成后，其他设备需要重新登录。如非本人操作，请忽略本邮件。"
                        : "请在登录页面输入下方验证码，继续你的 AI 工作之旅。");
    }
}
