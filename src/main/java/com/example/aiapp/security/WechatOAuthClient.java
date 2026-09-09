package com.example.aiapp.security;

import com.example.aiapp.config.WechatLoginProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.BAD_GATEWAY;

@Component
public class WechatOAuthClient {
    private final WechatLoginProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    public WechatOAuthClient(WechatLoginProperties properties, ObjectMapper mapper) {
        this.properties = properties; this.mapper = mapper;
    }
    public String exchange(String code) {
        try {
            URI uri = URI.create("https://api.weixin.qq.com/sns/oauth2/access_token?appid=" + enc(properties.appId())
                    + "&secret=" + enc(properties.appSecret()) + "&code=" + enc(code) + "&grant_type=authorization_code");
            var response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw unavailable();
            return parseOpenId(mapper.readTree(response.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw unavailable();
        } catch (Exception e) {
            // Never propagate request URIs, credentials, OAuth tokens or provider bodies.
            throw unavailable();
        }
    }
    static String parseOpenId(JsonNode body) {
        if (body == null || body.has("errcode") && body.path("errcode").asInt(-1) != 0
                || body.path("access_token").asText("").isBlank()
                || !body.path("openid").asText("").matches("[a-zA-Z0-9_-]{1,128}")
                || !java.util.Arrays.asList(body.path("scope").asText("").split(",")).contains("snsapi_login"))
            throw unavailable();
        return body.path("openid").asText();
    }
    static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(BAD_GATEWAY, "微信授权失败，请重新扫码。");
    }
}
