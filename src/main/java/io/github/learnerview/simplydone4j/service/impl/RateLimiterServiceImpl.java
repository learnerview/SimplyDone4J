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
    private final ConcurrentMap<String, int[]> fallbackCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> fallbackResetAt = new ConcurrentHashMap<>();
    private DefaultRedisScript<List> rateLimitScript;

    public RateLimiterServiceImpl(StringRedisTemplate redis, SimplyDoneProperties config) {
        this.redis = redis;
        this.config = config;
    }

    @PostConstruct
    void initScript() {
        try {
            rateLimitScript = new DefaultRedisScript<>();
            rateLimitScript.setScriptSource(new org.springframework.scripting.support.ResourceScriptSource(
                    new ClassPathResource("scripts/rate_limit.lua")));
            rateLimitScript.setResultType(List.class);
            rateLimitScript.getScriptAsString();
        } catch (Exception e) {
            log.warn("Failed to load rate limit Lua script, falling back to inline logic: {}", e.getMessage());
            rateLimitScript = null;
        }
    }

    @Override
    public void checkRateLimit(String producer) {
        int windowSeconds = config.getRateLimit().getWindowSeconds();
        int maxRequests = config.getRateLimit().getRequestsPerMinute();
        long now = System.currentTimeMillis();

        try {
            String key = "simplydone4j:ratelimit:" + producer;

            if (rateLimitScript != null) {
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
                useInlineSlidingWindow(key, windowSeconds, maxRequests, now);
            }
        } catch (RateLimitExceededException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Redis rate limiter unavailable for producer {}, using fallback: {}", producer, e.getMessage());
            useFallbackRateLimit(producer, windowSeconds, maxRequests, now);
        }
    }

    private void useInlineSlidingWindow(String key, int windowSeconds, int maxRequests, long now) {
        long windowStart = now - (windowSeconds * 1000L);
        redis.opsForZSet().removeRangeByScore(key, 0, windowStart);
        Long count = redis.opsForZSet().zCard(key);
        if (count != null && count >= maxRequests) {
            var oldest = redis.opsForZSet().rangeWithScores(key, 0, 0);
            long oldestScore = oldest != null && !oldest.isEmpty()
                    ? oldest.iterator().next().getScore().longValue() : windowStart;
            long retryAfter = (oldestScore + windowSeconds * 1000L - now) / 1000L + 1;
            throw new RateLimitExceededException(Math.max(1, retryAfter));
        }
        redis.opsForZSet().add(key, String.valueOf(now), now);
        redis.expire(key, Duration.ofSeconds(windowSeconds * 2L));
    }

    private void useFallbackRateLimit(String producer, int windowSeconds, int maxRequests, long now) {
        long resetTime = now + windowSeconds * 1000L;
        Long existingReset = fallbackResetAt.get(producer);

        if (existingReset == null || existingReset < now) {
            Long prev = fallbackResetAt.putIfAbsent(producer, resetTime);
            if (prev == null) {
                fallbackCounters.put(producer, new int[]{1});
                return;
            }
            existingReset = prev;
        }

        int[] counter = fallbackCounters.computeIfAbsent(producer, k -> new int[]{0});
        if (++counter[0] > maxRequests) {
            long resetAt = fallbackResetAt.getOrDefault(producer, existingReset);
            int retryAfter = (int) ((resetAt - now) / 1000L) + 1;
            throw new RateLimitExceededException(retryAfter);
        }
    }
}
