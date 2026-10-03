package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.exception.RateLimitExceededException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link RedisRateLimiterStrategy} against a <em>real</em> Redis.
 *
 * <p>The existing {@code RateLimiterCircuitBreakerIntegrationTest} mocks
 * {@link StringRedisTemplate}, so it returns whatever the test hands it. That is
 * precisely why the production defect below survived a green suite: the real template
 * serialises script replies with {@code StringRedisSerializer}, so the Lua reply
 * arrives as {@code List<String>} even though the script returns integers.
 *
 * <p>Under Mockito the cast to {@code List<Long>} was harmless; against real Redis it
 * threw {@link ClassCastException} on the very first submission. The generic
 * {@code catch (Exception)} reported that as an infrastructure failure, so the circuit
 * breaker opened after five submissions and every replica silently degraded to the
 * per-JVM limiter -- admitting N x the configured limit on an N-replica deployment.
 */
class RedisRateLimiterRealRedisTest {

    private static final String HOST = System.getProperty("it.redis.host",
            System.getenv().getOrDefault("IT_REDIS_HOST", "localhost"));
    private static final int PORT = Integer.parseInt(System.getProperty("it.redis.port",
            System.getenv().getOrDefault("IT_REDIS_PORT", "16379")));

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    private RedisRateLimiterStrategy strategy;

    @BeforeAll
    static void connectToRedis() {
        boolean reachable;
        try {
            LettuceConnectionFactory factory = new LettuceConnectionFactory(
                    new RedisStandaloneConfiguration(HOST, PORT));
            factory.afterPropertiesSet();
            factory.getConnection().ping();
            connectionFactory = factory;
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable,
                "Needs a real Redis at " + HOST + ":" + PORT
                        + " (set -Dit.redis.host/-Dit.redis.port or IT_REDIS_HOST/IT_REDIS_PORT). "
                        + "A mock cannot exercise the serializer that caused the defect.");
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        SimplyDoneProperties props = new SimplyDoneProperties();
        props.getRateLimit().setRequestsPerMinute(5);
        props.getRateLimit().setWindowSeconds(10);
        strategy = new RedisRateLimiterStrategy(redis, props);
        strategy.initScript();
    }

    @Test
    @DisplayName("real Redis reply must deserialize without ClassCastException")
    void shouldReadRealRedisReplyWithoutCastingFailure() {
        // Before the fix the very first call threw ClassCastException: class
        // java.lang.String cannot be cast to class java.lang.Long, surfaced as
        // IllegalStateException("Redis rate limiter unavailable").
        for (int i = 0; i < 5; i++) {
            strategy.checkRateLimit("producer-a");
        }
        assertThrows(RateLimitExceededException.class, () -> strategy.checkRateLimit("producer-a"),
                "The 6th request in a 5-per-window limit must be rejected, which also "
                        + "proves the real reply was parsed rather than thrown");
    }

    @Test
    @DisplayName("requests sharing one millisecond must each consume a slot")
    void shouldCountConcurrentRequestsIndividually() throws Exception {
        // The Lua script used ZADD key now now -- score AND member both being the
        // timestamp. Concurrent submissions inside a single millisecond collapsed onto
        // one ZSET member, so ZCARD never rose and the whole burst was admitted.
        int limit = 5;
        int attempts = 40;
        SimplyDoneProperties burstProps = props(limit, 10);
        RedisRateLimiterStrategy limited =
                new RedisRateLimiterStrategy(redis, burstProps);
        limited.initScript();

        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                tasks.add(() -> {
                    try {
                        limited.checkRateLimit("burst-producer");
                        return Boolean.TRUE;
                    } catch (RateLimitExceededException e) {
                        return Boolean.FALSE;
                    }
                });
            }
            int allowed = 0;
            for (Future<Boolean> f : pool.invokeAll(tasks)) {
                if (Boolean.TRUE.equals(f.get())) {
                    allowed++;
                }
            }
            assertEquals(limit, allowed,
                    "Exactly the configured number of requests may pass; the limiter "
                            + "must not admit a sub-millisecond burst beyond its limit");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("circuit breaker must not record failures for valid Redis replies")
    void shouldKeepCircuitClosedOnValidReplies() {
        RateLimiterCircuitBreaker breaker = new RateLimiterCircuitBreaker(props(5, 10));
        RedisRateLimiterStrategy withBreaker =
                new RedisRateLimiterStrategy(redis, props(5, 10), breaker);
        withBreaker.initScript();

        for (int i = 0; i < 5; i++) {
            withBreaker.checkRateLimit("producer-b");
        }
        assertFalse(breaker.isOpen(),
                "Valid Redis replies must count as successes, so the breaker stays closed");
    }

    @Test
    @DisplayName("retry-after must be reported for a rejected producer")
    void shouldReportRetryAfterOnRejection() {
        SimplyDoneProperties props = props(1, 10);
        RedisRateLimiterStrategy single =
                new RedisRateLimiterStrategy(redis, props);
        single.initScript();

        single.checkRateLimit("producer-c");
        RateLimitExceededException rejection = assertThrows(
                RateLimitExceededException.class, () -> single.checkRateLimit("producer-c"),
                "The limit of 1 per window must reject the second request");
        assertTrue(rejection.getRetryAfterSeconds() >= 1,
                "Rejection must carry a positive retry-after hint");
    }

    private static SimplyDoneProperties props(int perWindow, int windowSeconds) {
        SimplyDoneProperties props = new SimplyDoneProperties();
        props.getRateLimit().setRequestsPerMinute(perWindow);
        props.getRateLimit().setWindowSeconds(windowSeconds);
        return props;
    }
}
