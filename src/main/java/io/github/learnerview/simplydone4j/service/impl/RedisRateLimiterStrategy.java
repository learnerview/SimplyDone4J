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

/**
 * Redis-backed rate limiter strategy using a Lua sliding-window script.
 *
 * <p>Circuit-breaker semantics are strictly infrastructure-only:
 * <ul>
 *   <li>{@code onSuccess()} is called when Redis returns a well-formed response —
 *       regardless of whether the producer was allowed or rejected.</li>
 *   <li>{@code onFailure()} is called only when a Redis/Lua infrastructure error
 *       occurs (connection refused, timeout, malformed response, etc.).</li>
 *   <li>A rate-limit rejection ({@code allowed == false}) is a legitimate business
 *       result and does <em>not</em> count as a circuit-breaker failure.</li>
 * </ul>
 * </p>
 *
 * <p>When the circuit is OPEN this strategy throws {@link IllegalStateException}
 * so that the composite {@link RateLimiterServiceImpl} falls back to the in-memory
 * strategy instead of hammering a degraded Redis cluster.</p>
 */
public class RedisRateLimiterStrategy implements RateLimiterStrategy {
    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiterStrategy.class);

    private final StringRedisTemplate redis;
    private final SimplyDoneProperties config;
    private final String keyPrefix;
    private final RateLimiterCircuitBreaker circuitBreaker;
    private DefaultRedisScript<List> rateLimitScript;

    public RedisRateLimiterStrategy(StringRedisTemplate redis, SimplyDoneProperties config) {
        this.redis = redis;
        this.config = config;
        this.keyPrefix = config.getKeyPrefix();
        this.circuitBreaker = new RateLimiterCircuitBreaker(config);
    }

    /** Package-private constructor for testing with a pre-built circuit breaker. */
    RedisRateLimiterStrategy(StringRedisTemplate redis, SimplyDoneProperties config,
                              RateLimiterCircuitBreaker circuitBreaker) {
        this.redis = redis;
        this.config = config;
        this.keyPrefix = config.getKeyPrefix();
        this.circuitBreaker = circuitBreaker;
    }

    @PostConstruct
    public void initScript() {
        try {
            rateLimitScript = new DefaultRedisScript<>();
            rateLimitScript.setScriptSource(new org.springframework.scripting.support.ResourceScriptSource(
                    new ClassPathResource("scripts/rate_limit.lua")));
            rateLimitScript.setResultType(List.class);
            rateLimitScript.getScriptAsString(); // validate the script is readable
            circuitBreaker.onScriptLoaded();
        } catch (Exception e) {
            log.warn("Failed to load rate limit Lua script: {}", e.getMessage());
            rateLimitScript = null;
            // Do NOT reset or close the circuit here — keep whatever state it was in.
        }
    }

    @Override
    public void checkRateLimit(String producer) throws RateLimitExceededException {
        // If the circuit is OPEN, fail fast without touching Redis.
        if (circuitBreaker.isOpen()) {
            throw new IllegalStateException(
                    "Circuit breaker OPEN for Redis rate limiter — retrying in "
                            + circuitBreaker.getRetryAfterSeconds() + "s");
        }

        if (rateLimitScript == null) {
            throw new IllegalStateException("Redis Lua script not initialized");
        }

        int windowSeconds = config.getRateLimit().getWindowSeconds();
        int maxRequests = config.getRateLimit().getRequestsPerMinute();
        long now = System.currentTimeMillis();
        String key = keyPrefix + ":ratelimit:" + producer;

        try {
            @SuppressWarnings("unchecked")
            List<Long> results = redis.execute(rateLimitScript, List.of(key),
                    String.valueOf(now),
                    String.valueOf(windowSeconds * 1000L),
                    String.valueOf(maxRequests));

            // Null or undersized response means the Lua script returned something
            // unexpected — treat this as an infrastructure failure.
            if (results == null || results.size() < 2) {
                throw new IllegalStateException("Invalid response from Redis rate limit script");
            }

            // Redis and Lua executed correctly — record as a circuit-breaker success
            // regardless of whether the producer is allowed or rejected.
            circuitBreaker.onSuccess();

            boolean allowed = results.get(0) == 1L;
            long oldestTimestamp = results.get(1);

            if (!allowed) {
                long retryAfter = (oldestTimestamp + windowSeconds * 1000L - now) / 1000L + 1;
                throw new RateLimitExceededException(Math.max(1, retryAfter));
            }

            // Only set expiry after we know the full request succeeded.
            redis.expire(key, Duration.ofSeconds(windowSeconds * 2L));

        } catch (RateLimitExceededException e) {
            // Business rejection — not an infrastructure error. Re-throw as-is.
            throw e;
        } catch (Exception e) {
            // Any other exception is an infrastructure failure.
            circuitBreaker.onFailure(circuitBreaker.getRetryAfterSeconds());
            throw new IllegalStateException("Redis rate limiter unavailable: " + e.getMessage(), e);
        }
    }
}
