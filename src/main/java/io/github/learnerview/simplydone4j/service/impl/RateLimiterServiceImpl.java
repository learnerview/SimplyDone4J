package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.exception.RateLimitExceededException;
import io.github.learnerview.simplydone4j.service.RateLimiterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class RateLimiterServiceImpl implements RateLimiterService {
    private static final Logger log = LoggerFactory.getLogger(RateLimiterServiceImpl.class);

    private final StringRedisTemplate redis;
    private final SimplyDoneProperties config;
    private final String keyPrefix;

    /**
     * In-memory fallback: key → long[]{windowResetEpochMs, requestCount}
     * Access is serialised per-key via {@link ConcurrentHashMap#compute}, which
     * holds an exclusive lock on the bucket for the duration of the lambda.
     */
    private final ConcurrentMap<String, long[]> fallbackWindows = new ConcurrentHashMap<>();
    private DefaultRedisScript<List> rateLimitScript;

    public RateLimiterServiceImpl(StringRedisTemplate redis, SimplyDoneProperties config) {
        this.redis = redis;
        this.config = config;
        this.keyPrefix = config.getKeyPrefix();
    }

    @PostConstruct
    void initScript() {
        try {
            rateLimitScript = new DefaultRedisScript<>();
            rateLimitScript.setScriptSource(new org.springframework.scripting.support.ResourceScriptSource(
                    new ClassPathResource("scripts/rate_limit.lua")));
            rateLimitScript.setResultType(List.class);
            // Eagerly load to surface I/O errors at startup rather than at first request
            rateLimitScript.getScriptAsString();
        } catch (Exception e) {
            log.warn("Failed to load rate limit Lua script, falling back to in-memory logic: {}", e.getMessage());
            rateLimitScript = null;
        }
    }

    @Override
    public void checkRateLimit(String producer) {
        int windowSeconds = config.getRateLimit().getWindowSeconds();
        int maxRequests = config.getRateLimit().getRequestsPerMinute();
        long now = System.currentTimeMillis();

        try {
            String key = keyPrefix + ":ratelimit:" + producer;

            if (rateLimitScript != null) {
                @SuppressWarnings("unchecked")
                List<Long> results = redis.execute(rateLimitScript, List.of(key),
                        String.valueOf(now), String.valueOf(windowSeconds * 1000L),
                        String.valueOf(maxRequests));

                if (results != null && results.size() >= 2) {
                    boolean allowed = results.get(0) == 1L;
                    long oldestTimestamp = results.get(1);
                    if (!allowed) {
                        long retryAfter = (oldestTimestamp + windowSeconds * 1000L - now) / 1000L + 1;
                        throw new RateLimitExceededException(Math.max(1, retryAfter));
                    }
                }
                redis.expire(key, Duration.ofSeconds(windowSeconds * 2L));
            } else {
                log.warn("Rate limit Lua script not loaded, using in-memory fallback for producer {}", producer);
                useFallbackRateLimit(producer, windowSeconds, maxRequests, now);
            }
        } catch (RateLimitExceededException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Redis rate limiter unavailable for producer {}, using fallback: {}", producer, e.getMessage());
            useFallbackRateLimit(producer, windowSeconds, maxRequests, now);
        }
    }

    /**
     * Thread-safe in-memory sliding-window fallback.
     * <p>
     * Uses {@link ConcurrentHashMap#compute} which holds an exclusive lock on the
     * entry for the duration of the lambda, making the read-modify-write atomic
     * per producer key without a global lock.
     */
    private void useFallbackRateLimit(String producer, int windowSeconds, int maxRequests, long now) {
        long windowEndMs = now + windowSeconds * 1000L;

        // long[0] = window reset epoch ms, long[1] = request count in current window
        long[] window = fallbackWindows.compute(producer, (k, existing) -> {
            if (existing == null || existing[0] < now) {
                // Start a fresh window with this request as the first
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
