package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.exception.RateLimitExceededException;
import io.github.learnerview.simplydone4j.service.RateLimiterStrategy;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class InMemoryRateLimiterStrategy implements RateLimiterStrategy {

    private final SimplyDoneProperties config;
    private final ConcurrentMap<String, long[]> fallbackWindows = new ConcurrentHashMap<>();

    public InMemoryRateLimiterStrategy(SimplyDoneProperties config) {
        this.config = config;
    }

    @Override
    public void checkRateLimit(String producer) throws RateLimitExceededException {
        int windowSeconds = config.getRateLimit().getWindowSeconds();
        int maxRequests = config.getRateLimit().getRequestsPerMinute();
        long now = System.currentTimeMillis();

        long windowEndMs = now + windowSeconds * 1000L;

        // long[0] = window reset epoch ms, long[1] = request count in current window
        long[] window = fallbackWindows.compute(producer, (k, existing) -> {
            if (existing == null || existing[0] < now) {
                return new long[]{windowEndMs, 1};
            }
            existing[1]++;
            return existing;
        });

        if (window[1] > maxRequests) {
            long retryAfterMs = window[0] - now;
            long retryAfterSecs = retryAfterMs / 1000L + 1;
            throw new RateLimitExceededException(Math.max(1, retryAfterSecs));
        }
    }
}
