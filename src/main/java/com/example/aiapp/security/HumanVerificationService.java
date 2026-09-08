package com.example.aiapp.security;

import cloud.tianai.captcha.application.vo.ImageCaptchaVO;
import cloud.tianai.captcha.validator.common.model.dto.ImageCaptchaTrack;
import com.example.aiapp.config.RateLimitProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@Service
public class HumanVerificationService {
    private final CaptchaGateway gateway;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final ClientIpResolver ipResolver;
    private final SecureRandom random = new SecureRandom();
    record ChallengeContext(String ip, int width, int height, int pieceWidth, int pieceHeight, long issuedAt) {}
    public HumanVerificationService(CaptchaGateway gateway, StringRedisTemplate redis, ObjectMapper json, RateLimitProperties rates) {
        this.gateway = gateway; this.redis = redis; this.json = json;
        this.ipResolver = new ClientIpResolver(rates.trustedProxyCidrs());
    }
    public Map<String, Object> options() {
        return Map.of("provider", "tianai", "configured", true);
    }
    public ImageCaptchaVO challenge(HttpServletRequest request) {
        return challenge(request, null);
    }
    public ImageCaptchaVO challenge(HttpServletRequest request, String previousBackground) {
        try {
            // Server-only recent target; never accept an answer coordinate from the client.
            String recentKey = "lyle:captcha:recent-position:" + ip(request);
            String previous = redis.opsForValue().get(recentKey);
            ImageCaptchaVO image = null;
            for (int attempt = 0; attempt < 16; attempt++) {
                var candidate = gateway.generate(previousBackground);
                String imageKey = "lyle:captcha:image:" + candidate.getId();
                var secret = json.readValue(redis.opsForValue().get(imageKey), cloud.tianai.captcha.common.AnyMap.class);
                float target = secret.getFloat("percentage") * candidate.getBackgroundImageWidth();
                if (previous == null || Math.abs(target - Float.parseFloat(previous)) >= 40) {
                    image = candidate;
                    redis.opsForValue().set(recentKey, Float.toString(target), Duration.ofMinutes(2));
                    break;
                }
                redis.opsForValue().getAndDelete(imageKey);
            }
            if (image == null) throw new IllegalStateException("Could not generate a distinct puzzle");
            var context = new ChallengeContext(ip(request), image.getBackgroundImageWidth(), image.getBackgroundImageHeight(),
                    image.getTemplateImageWidth(), image.getTemplateImageHeight(), System.currentTimeMillis());
            redis.opsForValue().set("lyle:captcha:context:" + image.getId(), json.writeValueAsString(context), Duration.ofMinutes(2));
            return image;
        } catch (Exception e) { throw unavailable(); }
    }
    public Map<String,Object> solve(String id, ImageCaptchaTrack track, HttpServletRequest request) {
        if (id == null || !id.matches("SLIDER_[a-fA-F0-9]{32}")) throw invalid();
        try {
            // Consume before evaluating any answer, including malformed/wrong answers.
            String raw = redis.opsForValue().getAndDelete("lyle:captcha:context:" + id);
            if (raw == null) throw invalid();
            ChallengeContext context = json.readValue(raw, ChallengeContext.class);
            long elapsed = System.currentTimeMillis() - context.issuedAt();
            if (!context.ip().equals(ip(request)) || elapsed < 0 || elapsed > 120000 || !validTrack(track, context)) throw invalid();
            // Never trust dimensions submitted by the client.
            track.setBgImageWidth(context.width()); track.setBgImageHeight(context.height());
            track.setTemplateImageWidth(context.pieceWidth()); track.setTemplateImageHeight(context.pieceHeight());
            track.setData(null);
            if (!gateway.matches(id, track)) throw invalid();
            byte[] bytes = new byte[32]; random.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            redis.opsForValue().set("lyle:captcha:proof:" + digest(token), context.ip(), Duration.ofMinutes(2));
            return Map.of("captchaToken", token, "expiresInSeconds", 120);
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception e) { throw unavailable(); }
    }
    static boolean validTrack(ImageCaptchaTrack track, ChallengeContext c) {
        if (track == null || track.getStartTime() == null || track.getStopTime() == null
                || track.getStopTime() <= track.getStartTime()
                || track.getStopTime() - track.getStartTime() > 120000
                || track.getTrackList() == null || track.getTrackList().size() < 2 || track.getTrackList().size() > 256) return false;
        float previous = -1;
        for (var p : track.getTrackList()) {
            if (p == null || p.getX() == null || p.getY() == null || p.getT() == null
                    || !Float.isFinite(p.getX()) || !Float.isFinite(p.getY()) || !Float.isFinite(p.getT())
                    || p.getX() < 0 || p.getX() > c.width() - c.pieceWidth()
                    || Math.abs(p.getY()) > c.height() || p.getT() < previous || p.getT() > 120000) return false;
            previous = p.getT();
        }
        return track.getTrackList().get(0).getX() == 0f;
    }
    /** The second parameter is retained for wire compatibility; it never bypasses local proof validation. */
    public void verify(String token, String ignored, HttpServletRequest request) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalid();
        try {
            String owner = redis.opsForValue().getAndDelete("lyle:captcha:proof:" + digest(token));
            if (owner == null || !owner.equals(ip(request))) throw invalid();
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception e) { throw unavailable(); }
    }
    private String ip(HttpServletRequest request) { return digest(ipResolver.resolve(request)); }
    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static ResponseStatusException invalid() { return new ResponseStatusException(BAD_REQUEST, "安全验证未通过、已使用或已过期，请重新验证。"); }
    private static ResponseStatusException unavailable() { return new ResponseStatusException(SERVICE_UNAVAILABLE, "安全验证服务暂不可用，请稍后重试。"); }
}
