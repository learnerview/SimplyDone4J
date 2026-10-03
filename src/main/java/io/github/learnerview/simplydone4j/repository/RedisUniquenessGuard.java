package io.github.learnerview.simplydone4j.repository;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.service.UniquenessGuard;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

/**
 * Redis-backed {@link UniquenessGuard} using {@code SET key value NX PX ttl}.
 *
 * <p>Acquire is a single round trip and is atomic, which is the whole point: a
 * {@code GET} followed by a {@code SET} would let two workers both observe a free key.
 *
 * <p>Release is a compare-and-delete Lua script rather than {@code GET} then
 * {@code DEL} for the same reason. The delete is also fenced by owner, so a worker whose
 * TTL has already lapsed cannot evict the lock a different job has since taken.</p>
 */
public final class RedisUniquenessGuard implements UniquenessGuard {

    private static final RedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final String keyPrefix;

    public RedisUniquenessGuard(StringRedisTemplate redis, SimplyDoneProperties props) {
        this.redis = redis;
        this.keyPrefix = props.getKeyPrefix() + ":unique:";
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean tryAcquire(String uniqueKey, String owner, Duration ttl) {
        if (uniqueKey == null || uniqueKey.isBlank()) return true;
        Boolean acquired = redis.opsForValue().setIfAbsent(lockKey(uniqueKey), owner, ttl);
        return Boolean.TRUE.equals(acquired);
    }

    @Override
    public boolean release(String uniqueKey, String owner) {
        if (uniqueKey == null || uniqueKey.isBlank()) return true;
        Long deleted = redis.execute(RELEASE_SCRIPT, List.of(lockKey(uniqueKey)), owner);
        return deleted != null && deleted > 0;
    }

    private String lockKey(String uniqueKey) {
        return keyPrefix + uniqueKey;
    }
}
