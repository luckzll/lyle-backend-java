package com.example.aiapp.security;

import cloud.tianai.captcha.application.ImageCaptchaApplication;
import cloud.tianai.captcha.application.TACBuilder;
import cloud.tianai.captcha.application.vo.ImageCaptchaVO;
import cloud.tianai.captcha.resource.common.model.dto.Resource;
import cloud.tianai.captcha.validator.common.model.dto.ImageCaptchaTrack;
import org.springframework.stereotype.Component;

/** Local Tianai engine. No remote API, credentials or client-supplied resource URLs. */
@Component
public class CaptchaGateway {
    private static final String[] BACKGROUNDS = {"cat", "shan-shui", "anime"};
    private final ImageCaptchaApplication[] engines;
    public CaptchaGateway(TianaiRedisStore store) {
        engines = new ImageCaptchaApplication[] {
            build(store, "META-INF/cut-image/resource/1.jpg"),
            build(store, "captcha/shan-shui-600.png"),
            build(store, "captcha/anime-600.png")
        };
    }
    private ImageCaptchaApplication build(TianaiRedisStore store, String path) {
        return TACBuilder.builder().prefix("lyle:captcha:image")
                .expire("SLIDER", 120000L).setCacheStore(store).addDefaultTemplate()
                .addResource("SLIDER", new Resource("classpath", path))
                .build();
    }
    public ImageCaptchaVO generate() {
        return generate(null);
    }
    public ImageCaptchaVO generate(String previousBackground) {
        // Explicitly exclude the previous background, independently of other users' requests.
        var candidates = java.util.stream.IntStream.range(0, BACKGROUNDS.length)
                .filter(i -> !BACKGROUNDS[i].equals(previousBackground)).toArray();
        int index = candidates[java.util.concurrent.ThreadLocalRandom.current().nextInt(candidates.length)];
        var result = engines[index].generateCaptcha("SLIDER");
        if (!result.isSuccess() || result.getData() == null) throw new IllegalStateException("Captcha generation failed");
        result.getData().setBackgroundImageTag(BACKGROUNDS[index]);
        return result.getData();
    }
    public boolean matches(String id, ImageCaptchaTrack track) {
        return engines[0].matching(id, track).isSuccess();
    }
}
