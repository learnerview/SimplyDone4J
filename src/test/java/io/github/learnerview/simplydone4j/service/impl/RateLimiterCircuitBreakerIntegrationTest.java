package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.exception.RateLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests the end-to-end interaction between RedisRateLimiterStrategy,
 * RateLimiterCircuitBreaker, and the composite RateLimiterServiceImpl.
 *
 * Key semantics under test:
 *  - onSuccess() is called for ANY valid Redis/Lua response (allowed OR rejected).
 *  - onFailure() is called ONLY for infrastructure errors (null response, exception).
 *  - A business rate-limit rejection does NOT contribute to circuit-breaker failures.
 */
@ExtendWith(MockitoExtension.class)
class RateLimiterCircuitBreakerIntegrationTest {

    @Mock StringRedisTemplate redis;
    @Mock RateLimiterCircuitBreaker circuitBreaker;

    SimplyDoneProperties props;
    RedisRateLimiterStrategy redisStrategy;

    @BeforeEach
    void setUp() {
        props = new SimplyDoneProperties();
        props.getRateLimit().setRequestsPerMinute(5);
        props.getRateLimit().setWindowSeconds(10);
        // Use package-private constructor to inject a mock circuit breaker.
        // We must also call initScript() so rateLimitScript is non-null.
        redisStrategy = new RedisRateLimiterStrategy(redis, props, circuitBreaker);
        redisStrategy.initScript(); // loads the Lua script from classpath
    }

    @Test
    void shouldCallOnSuccessWhenRedisAllowsRequest() {
        when(circuitBreaker.isOpen()).thenReturn(false);
        when(redis.execute(any(), anyList(), anyString(), anyString(), anyString()))
                .thenReturn(List.of(1L, System.currentTimeMillis()));

        assertDoesNotThrow(() -> redisStrategy.checkRateLimit("producer-1"));

        verify(circuitBreaker).onSuccess();
        verify(circuitBreaker, never()).onFailure(anyLong());
    }

    @Test
    void shouldCallOnSuccessEvenWhenRateLimitExceeded() {
        // A rate-limit rejection is a valid Redis+Lua response — NOT an infra failure.
        when(circuitBreaker.isOpen()).thenReturn(false);
        long windowMs = props.getRateLimit().getWindowSeconds() * 1000L;
        long oldestTs = System.currentTimeMillis() - windowMs + 2000L;
        when(redis.execute(any(), anyList(), anyString(), anyString(), anyString()))
                .thenReturn(List.of(0L, oldestTs));

        // Business rejection is thrown, but circuit breaker must NOT record a failure.
        assertThrows(RateLimitExceededException.class, () -> redisStrategy.checkRateLimit("producer-1"));

        verify(circuitBreaker).onSuccess();       // ✅ valid Redis response
        verify(circuitBreaker, never()).onFailure(anyLong()); // ✅ not an infra error
    }

    @Test
    void shouldCallOnFailureWhenRedisThrowsInfrastructureError() {
        when(circuitBreaker.isOpen()).thenReturn(false);
        when(redis.execute(any(), anyList(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("Connection refused"));
        when(circuitBreaker.getRetryAfterSeconds()).thenReturn(30L);

        assertThrows(IllegalStateException.class, () -> redisStrategy.checkRateLimit("producer-1"));

        verify(circuitBreaker).onFailure(30L);
        verify(circuitBreaker, never()).onSuccess();
    }

    @Test
    void shouldCallOnFailureWhenLuaReturnsNullResponse() {
        when(circuitBreaker.isOpen()).thenReturn(false);
        when(redis.execute(any(), anyList(), anyString(), anyString(), anyString()))
                .thenReturn(null);
        when(circuitBreaker.getRetryAfterSeconds()).thenReturn(30L);

        assertThrows(IllegalStateException.class, () -> redisStrategy.checkRateLimit("producer-1"));

        verify(circuitBreaker).onFailure(30L);
        verify(circuitBreaker, never()).onSuccess();
    }

    @Test
    void shouldCallOnFailureWhenLuaReturnsMalformedResponse() {
        when(circuitBreaker.isOpen()).thenReturn(false);
        when(redis.execute(any(), anyList(), anyString(), anyString(), anyString()))
                .thenReturn(List.of()); // empty — not the expected [allowed, oldest]
        when(circuitBreaker.getRetryAfterSeconds()).thenReturn(30L);

        assertThrows(IllegalStateException.class, () -> redisStrategy.checkRateLimit("producer-1"));

        verify(circuitBreaker).onFailure(30L);
        verify(circuitBreaker, never()).onSuccess();
    }

    @Test
    void shouldThrowIllegalStateWhenCircuitIsOpenAndNotTouchRedis() {
        when(circuitBreaker.isOpen()).thenReturn(true);
        when(circuitBreaker.getRetryAfterSeconds()).thenReturn(30L);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> redisStrategy.checkRateLimit("producer-1"));

        assertTrue(ex.getMessage().contains("OPEN"));
        verifyNoInteractions(redis);
    }

    @Test
    void shouldFallbackToInMemoryWhenCircuitIsOpenViaComposite() {
        // Full composite: when RedisStrategy throws (circuit open), composite uses InMemory.
        when(circuitBreaker.isOpen()).thenReturn(true);
        when(circuitBreaker.getRetryAfterSeconds()).thenReturn(30L);

        SimplyDoneProperties inMemoryProps = new SimplyDoneProperties();
        inMemoryProps.getRateLimit().setRequestsPerMinute(100);
        inMemoryProps.getRateLimit().setWindowSeconds(60);
        InMemoryRateLimiterStrategy inMemory = new InMemoryRateLimiterStrategy(inMemoryProps);
        RateLimiterServiceImpl composite = new RateLimiterServiceImpl(redisStrategy, inMemory);

        // Should NOT throw — fallback to in-memory is within limit.
        assertDoesNotThrow(() -> composite.checkRateLimit("producer-1"));
    }
}
