package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.exception.RateLimitExceededException;
import io.github.learnerview.simplydone4j.service.RateLimiterStrategy;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-JVM fallback used while the Redis-backed limiter is unavailable.
 *
 * <p>Deliberately stricter than the Redis path: this is a <em>fixed</em> window, whereas the
 * Lua limiter implements a sliding log. At a window boundary a sliding limiter can admit
 * {@code limit} requests plus whatever drains during the window, while a fixed window admits
 * a full {@code limit} on each side of the boundary. Erring toward rejection during a Redis
 * outage is the safer direction to fail.</p>
 */
public class InMemoryRateLimiterStrategy implements RateLimiterStrategy {

    /**
     * Evict expired windows once every N calls. Sweeping on every call would make each
     * request O(producers); amortising keeps the common path O(1) while still bounding the
     * map so a large producer cardinality cannot grow it without limit.
     */
    private static final long SWEEP_INTERVAL = 1000L;

    private final SimplyDoneProperties config;
    private final ConcurrentMap<String, long[]> fallbackWindows = new ConcurrentHashMap<>();
    private final AtomicLong sweepCounter = new AtomicLong();

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

        if (sweepCounter.incrementAndGet() % SWEEP_INTERVAL == 0) {
            evictExpired(now);
        }

        if (window[1] > maxRequests) {
            long retryAfterMs = window[0] - now;
            long retryAfterSecs = retryAfterMs / 1000L + 1;
            throw new RateLimitExceededException(Math.max(1, retryAfterSecs));
        }
    }

    private void evictExpired(long now) {
        fallbackWindows.entrySet().removeIf(entry -> entry.getValue()[0] < now);
    }
}
