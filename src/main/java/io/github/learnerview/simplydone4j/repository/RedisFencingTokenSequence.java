package io.github.learnerview.simplydone4j.repository;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;

/**
 * Redis-backed fencing tokens backed by a single {@code INCR} counter.
 *
 * <p>{@code INCR} is atomic on a single key, so every instance in the cluster
 * draws from one totally ordered sequence without any additional coordination.
 * The counter is namespaced by {@code keyPrefix} so two tenants sharing one Redis
 * instance never share a sequence.</p>
 *
 * <p>Across a Sentinel failover the guarantee needs one more step. The increment happens
 * on the master; if that master dies before the write reaches a replica, the promoted
 * master can hand out the same number again, and a token that was already used would
 * come back. Waiting for a replica to acknowledge the increment closes that window: an
 * acknowledged write is already in the replica's replication stream, so promoting that
 * replica preserves the sequence.</p>
 *
 * <p>The guarantee is bounded, and the bound is worth stating plainly:</p>
 * <ul>
 *   <li>the wait is best-effort -- if it times out or no replica acknowledges, the token
 *       is still issued, because refusing to issue a token would stop the engine, and a
 *       duplicated token after a failover is rarer than a stalled engine;</li>
 *   <li>it only helps if the promoted replica is one that acknowledged the write, which
 *       is the usual case (Sentinel promotes the most up-to-date replica) but not
 *       guaranteed;</li>
 *   <li>on a single-node Redis there is no replica to acknowledge the write, so
 *       {@code WAIT} would block until its timeout; leave {@code wait-for-replicas} at
 *       its default of {@code 0} there.</li>
 * </ul>
 *
 * <p>For a hard guarantee, configure the server instead: {@code min-replicas-to-write}
 * together with {@code min-replicas-max-lag} makes the master refuse writes while too
 * few replicas are in sync, which turns "the token might be lost" into "the write is
 * refused". That is a server-side trade-off -- the engine stops accepting work when the
 * replica set is unhealthy -- and it is the operator's call.</p>
 *
 * <p>The token is rendered as a zero-padded decimal string of fixed width so that
 * {@link String#compareTo} agrees with numeric order. Callers can therefore treat
 * tokens both as opaque identifiers ({@code equals}) and as an ordering
 * ({@code compareTo} / greater-than), which a bare UUID could not support.</p>
 */
public final class RedisFencingTokenSequence implements FencingTokenSequence {

    private static final Logger log = LoggerFactory.getLogger(RedisFencingTokenSequence.class);

    /** {@link Long#MAX_VALUE} is 19 digits; padding to that width keeps lexical order aligned with numeric order. */
    private static final int TOKEN_WIDTH = 19;

    private final StringRedisTemplate redis;
    private final String counterKey;
    private final int waitForReplicas;
    private final long waitTimeoutMs;

    public RedisFencingTokenSequence(StringRedisTemplate redis, SimplyDoneProperties props) {
        this.redis = redis;
        this.counterKey = props.getKeyPrefix() + ":fencing:seq";
        this.waitForReplicas = Math.max(0, props.getFencing().getWaitForReplicas());
        this.waitTimeoutMs = Math.max(0, props.getFencing().getWaitTimeoutMs());
    }

    @Override
    public String nextToken() {
        // INCR and WAIT must run on the same connection. WAIT acknowledges the writes
        // already processed by this connection's master; issuing it on a pooled
        // connection that did not perform the increment asks the wrong connection to
        // wait, so the acknowledgement does not cover the token.
        Long next = redis.execute((RedisCallback<Long>) connection -> {
            Long incremented = connection.stringCommands()
                    .incr(counterKey.getBytes(StandardCharsets.UTF_8));
            if (incremented != null) {
                awaitReplication(connection);
            }
            return incremented;
        });
        if (next == null) {
            throw new IllegalStateException("Redis did not return a fencing token for key " + counterKey);
        }
        String digits = Long.toString(next);
        if (digits.length() >= TOKEN_WIDTH) {
            return digits;
        }
        return "0".repeat(TOKEN_WIDTH - digits.length()) + digits;
    }

    /**
     * Waits for the replica(s) to acknowledge the increment, so a promotion cannot hand
     * out the same token again.
     *
     * <p>Best-effort by design: a failure here means the token is less durable than it
     * would be with an acknowledgement, not that the engine cannot work. Blocking here
     * would stall every claim on an unhealthy replica set, which is a worse failure than
     * the one the wait protects against.</p>
     */
    private void awaitReplication(RedisConnection connection) {
        if (waitForReplicas <= 0) {
            return;
        }
        try {
            connection.execute("WAIT",
                    String.valueOf(waitForReplicas).getBytes(StandardCharsets.UTF_8),
                    String.valueOf(waitTimeoutMs).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // The token was already issued by INCR on the master; a failed acknowledgement
            // wait only weakens the failover guarantee.
            log.debug("WAIT replication acknowledgement timed out or failed: {}", e.getMessage());
        }
    }
}
