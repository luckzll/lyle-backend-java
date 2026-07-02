package com.example.aiapp.infrastructure.cache;

import com.example.aiapp.config.RedisProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class CacheService {

    private final ConcurrentHashMap<String, String> localCache = new ConcurrentHashMap<>();
    private final StringRedisTemplate redisTemplate;
    private final RedisProperties properties;

    public CacheService(ObjectProvider<StringRedisTemplate> redisTemplate, RedisProperties properties) {
        this.redisTemplate = redisTemplate.getIfAvailable();
        this.properties = properties;
    }

    public void set(String key, String value, Duration ttl) {
        if (isRedisEnabled()) {
            redisTemplate.opsForValue().set(key, value, ttl);
            return;
        }
        localCache.put(key, value);
    }

    public Optional<String> get(String key) {
        if (isRedisEnabled()) {
            return Optional.ofNullable(redisTemplate.opsForValue().get(key));
        }
        return Optional.ofNullable(localCache.get(key));
    }

    public void delete(String key) {
        if (isRedisEnabled()) {
            redisTemplate.delete(key);
            return;
        }
        localCache.remove(key);
    }

    private boolean isRedisEnabled() {
        return properties.enabled() && redisTemplate != null;
    }
}
