package com.example.aiapp.security;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class FixedWindowRateLimiter {

    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public FixedWindowRateLimiter() {
        this(Clock.systemUTC());
    }

    FixedWindowRateLimiter(Clock clock) {
        this.clock = clock;
    }

    public boolean allow(String key, int limitPerMinute) {
        if (limitPerMinute <= 0) {
            return true;
        }
        long windowId = clock.millis() / 60_000;
        Window window = windows.compute(key, (ignored, current) -> {
            if (current == null || current.windowId != windowId) {
                return new Window(windowId);
            }
            return current;
        });
        return window.count.incrementAndGet() <= limitPerMinute;
    }

    private static final class Window {
        private final long windowId;
        private final AtomicInteger count = new AtomicInteger();

        private Window(long windowId) {
            this.windowId = windowId;
        }
    }
}
