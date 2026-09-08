package com.example.aiapp.controller;

import cloud.tianai.captcha.application.vo.ImageCaptchaVO;
import cloud.tianai.captcha.validator.common.model.dto.ImageCaptchaTrack;
import com.example.aiapp.security.HumanVerificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth/captcha")
public class CaptchaController {
    private final HumanVerificationService service;
    public CaptchaController(HumanVerificationService service) { this.service = service; }
    public record Answer(@NotBlank @Size(max=64) String id, @NotNull ImageCaptchaTrack track) {}
    public record ChallengeRequest(@Size(max=32) String previousBackground) {}
    @PostMapping("/challenge")
    public ImageCaptchaVO challenge(@Valid @RequestBody(required=false) ChallengeRequest body, HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return service.challenge(request, body == null ? null : body.previousBackground());
    }
    @PostMapping("/verify")
    public Map<String,Object> verify(@Valid @RequestBody Answer answer, HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return service.solve(answer.id(), answer.track(), request);
    }
}
