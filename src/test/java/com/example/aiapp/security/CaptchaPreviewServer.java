package com.example.aiapp.security;

import com.example.aiapp.config.RateLimitProperties;
import com.example.aiapp.controller.CaptchaController;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/**
 * Manual localhost-only UI preview. Real Tianai image engine, expiring in-memory TEST store.
 * Never scans the application, reads .env, contacts SMTP or accesses production.
 * Not included in the production JAR.
 */
public class CaptchaPreviewServer {
    record Entry(String value, long expiry) {}
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        var json = new ObjectMapper();
        Map<String,Entry> cache = new ConcurrentHashMap<>();
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String,String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        doAnswer(a -> {
            cache.entrySet().removeIf(e -> e.getValue().expiry() < System.currentTimeMillis());
            cache.put(a.getArgument(0), new Entry(a.getArgument(1), System.currentTimeMillis() + ((Duration)a.getArgument(2)).toMillis()));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
        when(values.get(anyString())).thenAnswer(a -> {
            Entry e = cache.get(a.getArgument(0)); return e != null && e.expiry() > System.currentTimeMillis() ? e.value() : null;
        });
        when(values.getAndDelete(anyString())).thenAnswer(a -> {
            Entry e = cache.remove(a.getArgument(0)); return e != null && e.expiry() > System.currentTimeMillis() ? e.value() : null;
        });
        var service = new HumanVerificationService(new CaptchaGateway(new TianaiRedisStore(redis,json)), redis,json,
                new RateLimitProperties(10,10,10,10,10,10,""));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",4194),0);
        server.createContext("/api/auth/captcha/", exchange -> {
            int status=200; Object result;
            try {
                if (!exchange.getRequestMethod().equals("POST")) throw new IllegalArgumentException();
                var request = new MockHttpServletRequest(); request.setRemoteAddr("127.0.0.1");
                if (exchange.getRequestURI().getPath().endsWith("/challenge")) {
                    var body=json.readTree(exchange.getRequestBody());
                    result=service.challenge(request, body == null ? null : body.path("previousBackground").asText(""));
                }
                else {
                    byte[] body=exchange.getRequestBody().readNBytes(16385);
                    if (body.length>16384) throw new IllegalArgumentException();
                    var answer=json.readValue(body, CaptchaController.Answer.class);
                    result=service.solve(answer.id(),answer.track(),request);
                }
            } catch (ResponseStatusException e) { status=e.getStatusCode().value(); result=Map.of("detail",e.getReason()); }
            catch (Exception e) { status=400; result=Map.of("detail","无效验证请求"); }
            byte[] bytes=json.writeValueAsBytes(result);
            exchange.getResponseHeaders().set("Content-Type","application/json;charset=UTF-8");
            exchange.getResponseHeaders().set("Cache-Control","no-store");
            exchange.sendResponseHeaders(status,bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        System.out.println("Tianai TEST preview on 127.0.0.1:4194; no SMTP or production data");
    }
}
