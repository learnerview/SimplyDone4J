package io.github.learnerview.simplydone4j.repository;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The Redis implementation of the idempotency namespace, and the only class that names
 * its keys.
 */
public class RedisIdempotencyStore implements IdempotencyStore {

    /**
     * The reservation marker. Job ids are generated UUIDv7 values, so a committed id can
     * never begin with this prefix.
     */
    private static final String PENDING_PREFIX = "pending:";

    /**
     * How long a reservation survives if the submitting process dies before committing
     * it. It is deliberately short: the actual job write and queue add happen within one
     * request, and a reservation that outlived its submitter would block the key.
     */
    private static final Duration PENDING_TTL = Duration.ofSeconds(60);

    /**
     * Claim, read and set in one script. Doing this as separate commands left a window
     * where the key expired between the failed {@code SET NX} and the follow-up
     * {@code GET}, so the caller saw "held by nobody" and two submissions could both
     * believe they owned the key.
     */
    private static final RedisScript<String> CLAIM_SCRIPT = new DefaultRedisScript<>("""
            local existing = redis.call('GET', KEYS[1])
            if existing then
                return existing
            end
            redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
            return ''
            """, String.class);

    /**
     * Compare-and-set from the reservation to the permanent mapping, refreshing the TTL
     * to the configured idempotency window.
     */
    private static final RedisScript<Long> COMMIT_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if current == ARGV[1] or current == ARGV[2] then
                redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3])
                return 1
            end
            return 0
            """, Long.class);

    /** Compare-and-delete, matching either the reservation or the committed id. */
    private static final RedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if current == ARGV[1] or current == ARGV[2] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final String keyPrefix;
    private final long ttlSeconds;
    private final long pendingTtlSeconds;

    public RedisIdempotencyStore(StringRedisTemplate redis, SimplyDoneProperties config) {
        this.redis = redis;
        this.keyPrefix = config.getKeyPrefix();
        // Floored at an hour, for the same reason RedisJobRepository floors its retention
        // window. Spring Data Redis maps a zero Duration onto a plain SET with no expiry at
        // all, so `idempotency-ttl-hours: 0` did not disable retention -- it made every
        // lock immortal. Because idempotencyKey is caller-supplied and clients legitimately
        // mint a fresh key per request, that grows Redis without bound on keys nothing will
        // ever read again. The property is expressed in hours, so 1 is the smallest value
        // it can express anyway.
        long fullTtlSeconds = Math.max(1, config.getIdempotencyTtlHours()) * 3600L;
        this.ttlSeconds = fullTtlSeconds;
        this.pendingTtlSeconds = Math.min(fullTtlSeconds, PENDING_TTL.toSeconds());
    }

    @Override
    public Optional<Reservation> claim(String producer, String idempotencyKey, String jobId) {
        String fullKey = key(producer, idempotencyKey);
        String reservation = pendingValue(jobId);
        String existing = redis.execute(CLAIM_SCRIPT, List.of(fullKey),
                reservation, String.valueOf(pendingTtlSeconds));

        if (existing == null) {
            throw new IllegalStateException("The idempotency claim script returned no result");
        }
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        if (existing.startsWith(PENDING_PREFIX)) {
            return Optional.of(new Reservation(existing.substring(PENDING_PREFIX.length()), true));
        }
        return Optional.of(new Reservation(existing, false));
    }

    @Override
    public boolean commit(String producer, String idempotencyKey, String jobId) {
        Long committed = redis.execute(COMMIT_SCRIPT, List.of(key(producer, idempotencyKey)),
                pendingValue(jobId), jobId, String.valueOf(ttlSeconds));
        return committed != null && committed == 1L;
    }

    @Override
    public boolean releaseIfOwnedBy(String producer, String idempotencyKey, String jobId) {
        Long released = redis.execute(RELEASE_SCRIPT, List.of(key(producer, idempotencyKey)),
                jobId, pendingValue(jobId));
        return released != null && released == 1L;
    }

    @Override
    public Optional<String> lookup(String producer, String idempotencyKey) {
        String value = redis.opsForValue().get(key(producer, idempotencyKey));
        if (value == null || value.startsWith(PENDING_PREFIX)) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    private String pendingValue(String jobId) {
        return PENDING_PREFIX + jobId;
    }

    /**
     * The one place the key's name is written down.
     */
    private String key(String producer, String idempotencyKey) {
        return keyPrefix + ":idempotency:" + producer + ":" + idempotencyKey;
    }
}
