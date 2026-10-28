package io.github.learnerview.simplydone4j.repository;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the Redis semantics {@link RedisIdempotencyStore} depends on.
 *
 * <p>Needs a real Redis because the whole subject is how the server treats a zero expiry:
 * {@code SET key value PX 0} is rejected outright, it is not treated as "no expiry".
 */
class RedisIdempotencyStoreRealRedisTest {

    private static final String HOST = System.getProperty("it.redis.host",
            System.getenv().getOrDefault("IT_REDIS_HOST", "localhost"));
    private static final int PORT = Integer.parseInt(System.getProperty("it.redis.port",
            System.getenv().getOrDefault("IT_REDIS_PORT", "16379")));

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

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
                        + " (set -Dit.redis.host/-Dit.redis.port). A mock cannot show that "
                        + "SET with a zero PX is rejected by the server.");
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
    }

    @Test
    @DisplayName("a zero idempotency TTL must not leave keys behind forever")
    void shouldNotLeaveIdempotencyKeysWithoutATtl() {
        // Pins the server behaviour the fix relies on. Spring Data Redis turns
        // Duration.ZERO into a plain SET with no expiry at all (TTL -1), so the key the
        // limiter would want to expire is instead immortal.
        redis.opsForValue().set("no-expiry-probe", "v");
        assertEquals(-1L, redis.getExpire("no-expiry-probe"),
                "precondition: a SET without an expiry persists forever");

        SimplyDoneProperties props = new SimplyDoneProperties();
        props.setIdempotencyTtlHours(0);
        IdempotencyStore store = new RedisIdempotencyStore(redis, props);

        // submit() calls acquireOrGetExisting with no try/catch, so whatever this does is
        // what every producer's submission does.
        var acquired = store.claim("producer", "key-1", "job-1");

        assertTrue(acquired.isEmpty(), "the lock must be acquired");
        assertTrue(redis.getExpire("simplydone4j:idempotency:producer:key-1") > 0,
                "idempotency-ttl-hours: 0 left the key with no expiry, so every distinct "
                        + "(producer, idempotencyKey) pair a client ever sends is retained for "
                        + "the life of the Redis instance -- unbounded growth on a key nobody "
                        + "will ever read again");
    }

    @Test
    @DisplayName("a released lock must not delete a successor's lock")
    void shouldNotDeleteASuccessorsLock() {
        SimplyDoneProperties props = new SimplyDoneProperties();
        IdempotencyStore store = new RedisIdempotencyStore(redis, props);

        store.claim("producer", "key-2", "job-a");
        store.commit("producer", "key-2", "job-a");
        // The job expired but the lock outlived it, and a resubmission recreated it.
        assertEquals("job-a", redis.opsForValue().get("simplydone4j:idempotency:producer:key-2"));

        assertEquals(false, store.releaseIfOwnedBy("producer", "key-2", "job-b"),
                "precondition: a different job id must not release the lock");
        assertEquals("job-a", redis.opsForValue().get("simplydone4j:idempotency:producer:key-2"),
                "the lock must survive a mismatched release");
    }

    @Test
    @DisplayName("a pending reservation is not visible until it is committed")
    void shouldHideAPendingReservationFromLookup() {
        SimplyDoneProperties props = new SimplyDoneProperties();
        IdempotencyStore store = new RedisIdempotencyStore(redis, props);

        var claim = store.claim("producer", "key-3", "job-pending");

        assertTrue(claim.isEmpty(), "this call reserved the key, so there was no prior holder");
        assertTrue(store.lookup("producer", "key-3").isEmpty(),
                "a duplicate must not see the reserved job id before the original commits it");
        var duplicate = store.claim("producer", "key-3", "job-other").orElseThrow();
        assertTrue(duplicate.pending(), "the duplicate must see that a submission is in progress");
        assertEquals("job-pending", duplicate.jobId());
        assertTrue(store.commit("producer", "key-3", "job-pending"));
        assertEquals("job-pending", store.lookup("producer", "key-3").orElseThrow());
    }

    @Test
    @DisplayName("a committed key cannot be committed by a different job")
    void shouldRefuseToCommitSomebodyElsesReservation() {
        SimplyDoneProperties props = new SimplyDoneProperties();
        IdempotencyStore store = new RedisIdempotencyStore(redis, props);

        store.claim("producer", "key-4", "job-a");
        store.commit("producer", "key-4", "job-a");

        assertEquals(false, store.commit("producer", "key-4", "job-b"));
        assertEquals("job-a", store.lookup("producer", "key-4").orElseThrow());
    }
}