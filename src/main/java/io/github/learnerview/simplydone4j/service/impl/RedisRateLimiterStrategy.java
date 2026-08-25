package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.exception.RateLimitExceededException;
import io.github.learnerview.simplydone4j.service.RateLimiterStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.List;

public class RedisRateLimiterStrategy implements RateLimiterStrategy {
    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiterStrategy.class);

    private final StringRedisTemplate redis;
    private final SimplyDoneProperties config;
    private final String keyPrefix;
    private DefaultRedisScript<List> rateLimitScript;

    public RedisRateLimiterStrategy(StringRedisTemplate redis, SimplyDoneProperties config) {
        this.redis = redis;
        this.config = config;
        this.keyPrefix = config.getKeyPrefix();
    }

    @PostConstruct
    public void initScript() {
        try {
            rateLimitScript = new DefaultRedisScript<>();
            rateLimitScript.setScriptSource(new org.springframework.scripting.support.ResourceScriptSource(
                    new ClassPathResource("scripts/rate_limit.lua")));
            rateLimitScript.setResultType(List.class);
            rateLimitScript.getScriptAsString();
        } catch (Exception e) {
            log.warn("Failed to load rate limit Lua script: {}", e.getMessage());
            rateLimitScript = null;
        }
    }

    @Override
    public void checkRateLimit(String producer) throws RateLimitExceededException {
        if (rateLimitScript == null) {
            throw new IllegalStateException("Redis Lua script not initialized");
        }

        int windowSeconds = config.getRateLimit().getWindowSeconds();
        int maxRequests = config.getRateLimit().getRequestsPerMinute();
        long now = System.currentTimeMillis();
        String key = keyPrefix + ":ratelimit:" + producer;

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
    }
}
