package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.service.IdempotencyService;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

public class RedisIdempotencyServiceImpl implements IdempotencyService {

    private final StringRedisTemplate redis;
    private final String keyPrefix;
    private final int ttlHours;

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
    public Optional<String> acquireOrGetExisting(String producer, String idempotencyKey, String jobId) {
        String fullKey = keyPrefix + ":idempotency:" + producer + ":" + idempotencyKey;
        Boolean acquired = redis.opsForValue().setIfAbsent(fullKey, jobId, Duration.ofHours(ttlHours));
        if (Boolean.FALSE.equals(acquired)) {
            String existingJobId = redis.opsForValue().get(fullKey);
            return Optional.ofNullable(existingJobId);
        }
        return Optional.empty();
    }

    @Override
    public boolean releaseIfOwnedBy(String producer, String idempotencyKey, String jobId) {
        String fullKey = keyPrefix + ":idempotency:" + producer + ":" + idempotencyKey;
        // Compare-and-delete via WATCH so a concurrent resubmission that re-created the
        // key under the same job id cannot have its lock deleted out from under it.
        Boolean released = redis.execute(new SessionCallback<Boolean>() {
            @Override
            @SuppressWarnings("unchecked")
            public Boolean execute(RedisOperations ops) throws DataAccessException {
                ops.watch(fullKey);
                String current = (String) ops.opsForValue().get(fullKey);
                if (!jobId.equals(current)) {
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
}
