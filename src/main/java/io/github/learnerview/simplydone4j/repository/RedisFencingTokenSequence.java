package io.github.learnerview.simplydone4j.repository;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis-backed fencing tokens backed by a single {@code INCR} counter.
 *
 * <p>{@code INCR} is atomic on a single key, so every instance in the cluster
 * draws from one totally ordered sequence without any additional coordination.
 * The counter is namespaced by {@code keyPrefix} so two tenants sharing one Redis
 * instance never share a sequence.</p>
 *
 * <p>The token is rendered as a zero-padded decimal string of fixed width so that
 * {@link String#compareTo} agrees with numeric order. Callers can therefore treat
 * tokens both as opaque identifiers ({@code equals}) and as an ordering
 * ({@code compareTo} / greater-than), which a bare UUID could not support.</p>
 */
public final class RedisFencingTokenSequence implements FencingTokenSequence {

    /** {@link Long#MAX_VALUE} is 19 digits; padding to that width keeps lexical order aligned with numeric order. */
    private static final int TOKEN_WIDTH = 19;

    private final StringRedisTemplate redis;
    private final String counterKey;

    public RedisFencingTokenSequence(StringRedisTemplate redis, SimplyDoneProperties props) {
        this.redis = redis;
        this.counterKey = props.getKeyPrefix() + ":fencing:seq";
    }

    @Override
    public String nextToken() {
        Long next = redis.opsForValue().increment(counterKey);
        if (next == null) {
            throw new IllegalStateException("Redis did not return a fencing token for key " + counterKey);
        }
        String digits = Long.toString(next);
        if (digits.length() >= TOKEN_WIDTH) {
            return digits;
        }
        return "0".repeat(TOKEN_WIDTH - digits.length()) + digits;
    }
}
