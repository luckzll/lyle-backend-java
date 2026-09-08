package com.example.aiapp.security;

import cloud.tianai.captcha.cache.CacheStore;
import cloud.tianai.captcha.common.AnyMap;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Shared, expiring challenge storage; Redis failure must never fall back to process memory. */
@Component
public class TianaiRedisStore implements CacheStore {
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private static final DefaultRedisScript<Long> INCR = new DefaultRedisScript<>(
            "local n=redis.call('INCRBY',KEYS[1],ARGV[1]); redis.call('PEXPIRE',KEYS[1],ARGV[2]); return n", Long.class);
    public TianaiRedisStore(StringRedisTemplate redis, ObjectMapper json) { this.redis = redis; this.json = json; }
    private AnyMap decode(String value) {
        if (value == null) return null;
        try { return json.readValue(value, AnyMap.class); }
        catch (Exception e) { throw new IllegalStateException("Captcha cache unavailable"); }
    }
    public AnyMap getCache(String key) { return decode(redis.opsForValue().get(key)); }
    public AnyMap getAndRemoveCache(String key) { return decode(redis.opsForValue().getAndDelete(key)); }
    public boolean setCache(String key, AnyMap value, Long expiry, TimeUnit unit) {
        try { redis.opsForValue().set(key, json.writeValueAsString(value), Duration.ofMillis(unit.toMillis(expiry))); return true; }
        catch (Exception e) { throw new IllegalStateException("Captcha cache unavailable"); }
    }
    public Long incr(String key, long delta, Long expiry, TimeUnit unit) {
        return redis.execute(INCR, List.of(key), String.valueOf(delta), String.valueOf(unit.toMillis(expiry)));
    }
    public Long getLong(String key) { String value = redis.opsForValue().get(key); return value == null ? null : Long.valueOf(value); }
    public void close() { /* Redis lifecycle is owned by Spring. */ }
}
