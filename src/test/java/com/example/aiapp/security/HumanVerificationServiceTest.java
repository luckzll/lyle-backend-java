package com.example.aiapp.security;

import cloud.tianai.captcha.common.AnyMap;
import cloud.tianai.captcha.validator.common.model.dto.ImageCaptchaTrack;
import com.example.aiapp.config.RateLimitProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class HumanVerificationServiceTest {
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked") final ValueOperations<String,String> values = mock(ValueOperations.class);
    final ObjectMapper json = new ObjectMapper();
    final Map<String,String> cache = new ConcurrentHashMap<>();
    final MockHttpServletRequest request = new MockHttpServletRequest();
    HumanVerificationService service;
    @BeforeEach void setUp() {
        request.setRemoteAddr("192.0.2.10");
        when(redis.opsForValue()).thenReturn(values);
        doAnswer(a -> { cache.put(a.getArgument(0),a.getArgument(1)); return null; })
                .when(values).set(anyString(),anyString(),any(Duration.class));
        when(values.get(anyString())).thenAnswer(a -> cache.get(a.getArgument(0)));
        when(values.getAndDelete(anyString())).thenAnswer(a -> cache.remove(a.getArgument(0)));
        service = new HumanVerificationService(new CaptchaGateway(new TianaiRedisStore(redis,json)), redis, json,
                new RateLimitProperties(10,10,10,10,10,10,""));
    }
    ImageCaptchaTrack answer(String id, int width) throws Exception {
        // Test-only inspection of server cache; answers never appear in the HTTP challenge.
        AnyMap secret = json.readValue(cache.get("lyle:captcha:image:" + id), AnyMap.class);
        float x = secret.getFloat("percentage") * width;
        var track = new ImageCaptchaTrack(); track.setStartTime(100L); track.setStopTime(800L);
        track.setBgImageWidth(1); // Malicious client dimensions are ignored.
        track.setTrackList(List.of(new ImageCaptchaTrack.Track(0f,0f,0f,"down"),new ImageCaptchaTrack.Track(x,0f,700f,"up")));
        return track;
    }
    @Test void realEngineGeneratesImagesAndProofIsConsumedOnce() throws Exception {
        var image = service.challenge(request);
        assertTrue(image.getBackgroundImage().startsWith("data:image/"));
        assertTrue(image.getTemplateImage().startsWith("data:image/"));
        assertFalse(json.writeValueAsString(image).contains("percentage"));
        var result = service.solve(image.getId(),answer(image.getId(), image.getBackgroundImageWidth()),request);
        String token = (String) result.get("captchaToken");
        assertEquals(43, token.length());
        service.verify(token,null,request);
        assertEquals(400,assertThrows(ResponseStatusException.class,()->service.verify(token,null,request)).getStatusCode().value());
        assertEquals("tianai", service.options().get("provider"));
    }
    @Test void wrongAnswerConsumesChallenge() {
        var image = service.challenge(request);
        var track=new ImageCaptchaTrack(); track.setStartTime(1L); track.setStopTime(800L);
        track.setTrackList(List.of(new ImageCaptchaTrack.Track(0f,0f,0f,"down"),new ImageCaptchaTrack.Track(0f,0f,799f,"up")));
        assertThrows(ResponseStatusException.class,()->service.solve(image.getId(),track,request));
        assertFalse(cache.containsKey("lyle:captcha:context:"+image.getId()));
        assertThrows(ResponseStatusException.class,()->service.solve(image.getId(),track,request));
    }
    @Test void refreshAlwaysChangesBackgroundAndNewPuzzleStillVerifies() throws Exception {
        var first = service.challenge(request);
        var second = service.challenge(request, first.getBackgroundImageTag());
        assertNotEquals(first.getBackgroundImageTag(), second.getBackgroundImageTag());
        assertNotEquals(first.getId(), second.getId());
        assertNotEquals(first.getBackgroundImage(), second.getBackgroundImage());
        var result = service.solve(second.getId(), answer(second.getId(), second.getBackgroundImageWidth()), request);
        assertNotNull(result.get("captchaToken"));
        var third = service.challenge(request, second.getBackgroundImageTag());
        assertNotEquals(second.getBackgroundImageTag(), third.getBackgroundImageTag());
    }
    @Test void allBackgroundAssetsHaveCanonicalSize() throws Exception {
        for (String path : List.of("META-INF/cut-image/resource/1.jpg",
                "captcha/shan-shui-600.png", "captcha/anime-600.png")) {
            try (var input = getClass().getClassLoader().getResourceAsStream(path)) {
                assertNotNull(input, path);
                var image = javax.imageio.ImageIO.read(input);
                assertEquals(600, image.getWidth(), path);
                assertEquals(360, image.getHeight(), path);
            }
        }
        String previous = null;
        var positions = new java.util.HashSet<Float>();
        Float lastTarget = null;
        for (int i = 0; i < 12; i++) {
            var image = service.challenge(request, previous);
            assertNotEquals(previous, image.getBackgroundImageTag());
            assertTrue(List.of("cat", "shan-shui", "anime").contains(image.getBackgroundImageTag()));
            assertEquals(600, image.getBackgroundImageWidth());
            assertEquals(360, image.getBackgroundImageHeight());
            assertEquals(110, image.getTemplateImageWidth());
            assertEquals(360, image.getTemplateImageHeight());
            AnyMap secret = json.readValue(cache.get("lyle:captcha:image:" + image.getId()), AnyMap.class);
            positions.add(secret.getFloat("percentage"));
            float target = secret.getFloat("percentage") * 600;
            if (lastTarget != null) assertTrue(Math.abs(target - lastTarget) >= 40);
            lastTarget = target;
            assertNotNull(service.solve(image.getId(), answer(image.getId(), 600), request).get("captchaToken"));
            previous = image.getBackgroundImageTag();
        }
        assertTrue(positions.size() > 1, "Puzzle target positions must vary");
    }
    @Test void expiredChallengeAndMalformedTokensAreRejected() {
        assertThrows(ResponseStatusException.class,()->service.solve("SLIDER_"+"a".repeat(32),new ImageCaptchaTrack(),request));
        for (String token : new String[]{null, "", "forged", "a".repeat(43)}) assertThrows(ResponseStatusException.class,()->service.verify(token,null,request));
    }
    @Test void proofCannotMoveToAnotherIp() throws Exception {
        var image=service.challenge(request);
        var result=service.solve(image.getId(),answer(image.getId(),image.getBackgroundImageWidth()),request);
        request.setRemoteAddr("192.0.2.20");
        assertThrows(ResponseStatusException.class,()->service.verify((String)result.get("captchaToken"),null,request));
    }
    @Test void untrustedForwardedHeaderDoesNotChangeBinding() throws Exception {
        var image=service.challenge(request);
        var result=service.solve(image.getId(),answer(image.getId(),image.getBackgroundImageWidth()),request);
        request.addHeader("X-Forwarded-For","1.2.3.4");
        assertDoesNotThrow(()->service.verify((String)result.get("captchaToken"),null,request));
    }
    @Test void malformedTrackConsumesChallengeAndFailsClosed() {
        var image=service.challenge(request);
        var track=new ImageCaptchaTrack();track.setStartTime(1L);track.setStopTime(800L);
        track.setTrackList(List.of(new ImageCaptchaTrack.Track(0f,0f,0f,"down"),new ImageCaptchaTrack.Track(Float.NaN,0f,700f,"up")));
        assertThrows(ResponseStatusException.class,()->service.solve(image.getId(),track,request));
        assertFalse(cache.containsKey("lyle:captcha:context:"+image.getId()));
    }
    @Test void redisOutageCannotGenerateChallengeOrValidateProof() {
        when(values.getAndDelete(anyString())).thenThrow(new RuntimeException("private details"));
        var e=assertThrows(ResponseStatusException.class,()->service.verify("a".repeat(43),null,request));
        assertEquals(503,e.getStatusCode().value()); assertFalse(e.toString().contains("private"));
        doThrow(new RuntimeException("private")).when(values).set(anyString(),anyString(),any(Duration.class));
        assertEquals(503,assertThrows(ResponseStatusException.class,()->service.challenge(request)).getStatusCode().value());
    }
}
