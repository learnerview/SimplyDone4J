package io.github.learnerview.simplydone4j.repository;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.service.IdempotencyReservation;
import io.github.learnerview.simplydone4j.service.IdempotencyService;
import io.github.learnerview.simplydone4j.service.impl.RedisIdempotencyServiceImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the Redis semantics {@link RedisIdempotencyServiceImpl} depends on.
 *
 * <p>Needs a real Redis because the whole subject is how the server treats a zero expiry:
 * {@code SET key value PX 0} is rejected outright, it is not treated as "no expiry".
 */
class RedisIdempotencyServiceRealRedisTest {

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
        IdempotencyService service = new RedisIdempotencyServiceImpl(redis, props);

        // submit() calls acquireOrGetExisting with no try/catch, so whatever this does is
        // what every producer's submission does.
        Optional<IdempotencyReservation> acquired = service.acquireOrGetExisting("producer", "key-1", "job-1");

        assertTrue(acquired.isEmpty(), "the reservation must be acquired");
        assertEquals("pending:job-1", redis.opsForValue().get("simplydone4j:idempotency:producer:key-1"),
                "an unconfirmed reservation must hold the pending marker, not the bare job id");
        assertTrue(redis.getExpire("simplydone4j:idempotency:producer:key-1") > 0,
                "idempotency-ttl-hours: 0 left the key with no expiry, so every distinct "
                        + "(producer, idempotencyKey) pair a client ever sends is retained for "
                        + "the life of the Redis instance -- unbounded growth on a key nobody "
                        + "will ever read again");
    }

    @Test
    @DisplayName("a reservation is pending until the job is persisted, then promotes")
    void shouldPromotePendingReservationAfterConfirmation() {
        SimplyDoneProperties props = new SimplyDoneProperties();
        IdempotencyService service = new RedisIdempotencyServiceImpl(redis, props);

        service.acquireOrGetExisting("producer", "key-promote", "job-1");
        Optional<IdempotencyReservation> duplicate = service.acquireOrGetExisting("producer", "key-promote", "job-2");
        assertTrue(duplicate.isPresent());
        assertEquals("job-1", duplicate.get().jobId());
        assertTrue(duplicate.get().pending(),
                "the holder has not persisted its job yet, so the reservation is pending");

        assertTrue(service.confirmReservation("producer", "key-promote", "job-1"),
                "confirming with the owning job id must promote the reservation");
        assertEquals("job-1", redis.opsForValue().get("simplydone4j:idempotency:producer:key-promote"),
                "a confirmed reservation must hold the bare job id");

        Optional<IdempotencyReservation> afterConfirm =
                service.acquireOrGetExisting("producer", "key-promote", "job-3");
        assertTrue(afterConfirm.isPresent());
        assertEquals("job-1", afterConfirm.get().jobId());
        assertTrue(!afterConfirm.get().pending(),
                "a confirmed reservation must no longer read as pending");
    }

    @Test
    @DisplayName("a released lock must not delete a successor's lock")
    void shouldNotDeleteASuccessorsLock() {
        SimplyDoneProperties props = new SimplyDoneProperties();
        IdempotencyService service = new RedisIdempotencyServiceImpl(redis, props);

        service.acquireOrGetExisting("producer", "key-2", "job-a");
        // The job expired but the reservation outlived it, and a resubmission recreated it.
        assertEquals("pending:job-a", redis.opsForValue().get("simplydone4j:idempotency:producer:key-2"));

        assertEquals(false, service.releaseIfOwnedBy("producer", "key-2", "job-b"),
                "precondition: a different job id must not release the lock");
        assertEquals("pending:job-a", redis.opsForValue().get("simplydone4j:idempotency:producer:key-2"),
                "the lock must survive a mismatched release");
        assertEquals(true, service.releaseIfOwnedBy("producer", "key-2", "job-a"),
                "the owning job id must be able to release its own pending reservation");
    }
}