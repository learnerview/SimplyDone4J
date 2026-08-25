package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.service.IdempotencyService;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;

public class RedisIdempotencyServiceImpl implements IdempotencyService {

    private final StringRedisTemplate redis;
    private final String keyPrefix;
    private final int ttlHours;

    public RedisIdempotencyServiceImpl(StringRedisTemplate redis, SimplyDoneProperties config) {
        this.redis = redis;
        this.keyPrefix = config.getKeyPrefix();
        this.ttlHours = config.getIdempotencyTtlHours();
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
}
