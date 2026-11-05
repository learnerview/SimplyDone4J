package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.service.IdempotencyReservation;
import io.github.learnerview.simplydone4j.service.IdempotencyService;
import io.github.learnerview.simplydone4j.util.RedisKeyCodec;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

public class RedisIdempotencyServiceImpl implements IdempotencyService {

    /** Value prefix for a reservation whose job record does not exist yet. */
    public static final String PENDING_PREFIX = "pending:";

    private final StringRedisTemplate redis;
    private final String keyPrefix;
    private final int ttlHours;
    private static final RedisScript<String> ACQUIRE_OR_GET_SCRIPT = new DefaultRedisScript<>("""
            local existing = redis.call('GET', KEYS[1])
            if existing then
                return existing
            end
            local pending = 'pending:' .. ARGV[1]
            local created = redis.call('SET', KEYS[1], pending, 'NX', 'EX', ARGV[2])
            if created then
                return pending
            end
            return redis.call('GET', KEYS[1])
            """, String.class);

    public RedisIdempotencyServiceImpl(StringRedisTemplate redis, SimplyDoneProperties config) {
        this.redis = redis;
        this.keyPrefix = config.getKeyPrefix();
        // Floored at an hour, for the same reason RedisJobRepository floors its retention
        // window. Spring Data Redis maps a zero Duration onto a plain SET with no expiry at
        // all, so `idempotency-ttl-hours: 0` did not disable retention -- it made every
        // lock immortal. Because idempotencyKey is caller-supplied and clients legitimately
        // mint a fresh key per request, that grows Redis without bound on keys nothing will
        // ever read again. The property is expressed in hours, so 1 is the smallest value
        // it can express anyway.
        this.ttlHours = Math.max(1, config.getIdempotencyTtlHours());
    }

    @Override
    public Optional<IdempotencyReservation> acquireOrGetExisting(String producer, String idempotencyKey, String jobId) {
        String fullKey = RedisKeyCodec.idempotencyKey(
                keyPrefix + ":idempotency:", producer, idempotencyKey);
        String resolved;
        try {
            resolved = redis.execute(ACQUIRE_OR_GET_SCRIPT, List.of(fullKey),
                    jobId, String.valueOf(Duration.ofHours(ttlHours).toSeconds()));
        } catch (RuntimeException e) {
            releaseAfterAmbiguousAcquire(producer, idempotencyKey, jobId, e);
            throw e;
        }
        if (resolved == null) {
            IllegalStateException failure =
                    new IllegalStateException("Redis returned no idempotency owner for key " + fullKey);
            releaseAfterAmbiguousAcquire(producer, idempotencyKey, jobId, failure);
            throw failure;
        }
        if ((PENDING_PREFIX + jobId).equals(resolved)) {
            // This submission owns the reservation.
            return Optional.empty();
        }
        return Optional.of(parse(resolved));
    }

    @Override
    public boolean confirmReservation(String producer, String idempotencyKey, String jobId) {
        String fullKey = RedisKeyCodec.idempotencyKey(
                keyPrefix + ":idempotency:", producer, idempotencyKey);
        String pending = PENDING_PREFIX + jobId;
        Boolean confirmed = redis.execute(new SessionCallback<Boolean>() {
            @Override
            @SuppressWarnings("unchecked")
            public <K, V> Boolean execute(RedisOperations<K, V> genericOps) throws DataAccessException {
                @SuppressWarnings("unchecked")
                RedisOperations<String, String> ops =
                        (RedisOperations<String, String>) (RedisOperations<?, ?>) genericOps;
                ops.watch(fullKey);
                String current = ops.opsForValue().get(fullKey);
                if (jobId.equals(current)) {
                    ops.unwatch();
                    return true;
                }
                if (!pending.equals(current)) {
                    ops.unwatch();
                    return false;
                }
                Long remainingTtlSeconds = ops.getExpire(fullKey);
                ops.multi();
                ops.opsForValue().set(fullKey, jobId);
                if (remainingTtlSeconds != null && remainingTtlSeconds > 0) {
                    ops.expire(fullKey, Duration.ofSeconds(remainingTtlSeconds));
                } else {
                    // A missing or persistent key must still be bounded. Keep whatever TTL
                    // the reservation was created with; only fall back when there is none.
                    ops.expire(fullKey, Duration.ofHours(ttlHours));
                }
                List<Object> execResult = ops.exec();
                return execResult != null && !execResult.isEmpty();
            }
        });
        return Boolean.TRUE.equals(confirmed);
    }

    @Override
    public boolean releaseIfOwnedBy(String producer, String idempotencyKey, String jobId) {
        String fullKey = RedisKeyCodec.idempotencyKey(
                keyPrefix + ":idempotency:", producer, idempotencyKey);
        // Compare-and-delete via WATCH so a concurrent resubmission that re-created the
        // key under the same job id cannot have its lock deleted out from under it. Both the
        // pending and the promoted form of a job's reservation count as owned by that job.
        @SuppressWarnings({"unchecked","rawtypes"})
        Boolean released = redis.execute(new SessionCallback<Boolean>() {
            @Override
            @SuppressWarnings("unchecked")
            public <K, V> Boolean execute(RedisOperations<K, V> genericOps) throws DataAccessException {
                @SuppressWarnings("unchecked")
                RedisOperations<String, String> ops =
                        (RedisOperations<String, String>) (RedisOperations<?, ?>) genericOps;
                ops.watch(fullKey);
                String current = (String) ops.opsForValue().get(fullKey);
                if (!jobId.equals(current) && !(PENDING_PREFIX + jobId).equals(current)) {
                    ops.unwatch();
                    return false;
                }
                ops.multi();
                ops.delete(fullKey);
                List<Object> execResult = ops.exec();
                return execResult != null && !execResult.isEmpty();
            }
        });
        return Boolean.TRUE.equals(released);
    }

    private void releaseAfterAmbiguousAcquire(String producer, String idempotencyKey, String jobId,
                                              RuntimeException failure) {
        try {
            releaseIfOwnedBy(producer, idempotencyKey, jobId);
        } catch (RuntimeException releaseFailure) {
            failure.addSuppressed(releaseFailure);
        }
    }

    private IdempotencyReservation parse(String value) {
        if (value.startsWith(PENDING_PREFIX)) {
            return new IdempotencyReservation(value.substring(PENDING_PREFIX.length()), true);
        }
        return new IdempotencyReservation(value, false);
    }
}
