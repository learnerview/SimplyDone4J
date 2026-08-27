package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A simple three-state circuit breaker for the Redis rate limiter.
 *
 * <h3>State machine</h3>
 * <pre>
 *  CLOSED ──(failures >= threshold)──▶ OPEN
 *    ▲                                    │
 *    │                             (reset timeout elapsed)
 *    │                                    ▼
 *    └──────(success in HALF_OPEN)── HALF_OPEN
 *                                         │
 *                             (failure in HALF_OPEN)
 *                                         ▼
 *                                       OPEN
 * </pre>
 *
 * <h3>Thread safety</h3>
 * All state transitions use {@link AtomicReference} CAS to avoid races in
 * HALF_OPEN where multiple threads could simultaneously probe Redis.
 * Only one probe is allowed in HALF_OPEN — subsequent callers see OPEN until
 * the probe either succeeds (→ CLOSED) or fails (→ OPEN again).
 */
public final class RateLimiterCircuitBreaker {
    private static final Logger log = LoggerFactory.getLogger(RateLimiterCircuitBreaker.class);

    private final int failureThreshold;
    final long resetTimeoutSeconds;

    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicLong lastFailureTimeMs = new AtomicLong(0);
    private volatile long retryAfterSeconds = 30L;

    public RateLimiterCircuitBreaker(SimplyDoneProperties config) {
        this.failureThreshold = config.getRateLimit().getCircuitBreakerFailures() > 0
                ? config.getRateLimit().getCircuitBreakerFailures() : 5;
        this.resetTimeoutSeconds = config.getRateLimit().getCircuitBreakerResetSeconds() > 0
                ? config.getRateLimit().getCircuitBreakerResetSeconds() : 30L;
    }

    /** Called when the Lua script loads successfully at startup. */
    public void onScriptLoaded() {
        // Confirm successful initialization — keep (or return to) CLOSED.
        state.compareAndSet(State.OPEN, State.CLOSED);
        if (state.get() == State.CLOSED) {
            failureCount.set(0);
            retryAfterSeconds = 0L;
        }
    }

    /**
     * Returns {@code true} when the circuit is OPEN and requests should be
     * blocked immediately. Also handles the OPEN → HALF_OPEN transition using
     * CAS so only one thread gets through as the probe.
     */
    public boolean isOpen() {
        State current = state.get();
        if (current == State.OPEN) {
            long elapsedMs = System.currentTimeMillis() - lastFailureTimeMs.get();
            if (elapsedMs >= TimeUnit.SECONDS.toMillis(resetTimeoutSeconds)) {
                // CAS: only one thread transitions to HALF_OPEN — others still see OPEN.
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    log.info("Circuit breaker transitioning OPEN → HALF_OPEN for probe");
                }
            }
            // Re-read state after potential CAS: if we won the CAS we are now HALF_OPEN (not open),
            // if we lost the CAS another thread is already probing (still OPEN for us).
            return state.get() == State.OPEN;
        }
        return false;
    }

    /**
     * Records a successful Redis/Lua operation.
     * <ul>
     *   <li>HALF_OPEN → CLOSED (probe succeeded)</li>
     *   <li>CLOSED → resets failure counter</li>
     * </ul>
     */
    public void onSuccess() {
        State current = state.get();
        if (current == State.HALF_OPEN) {
            if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
                failureCount.set(0);
                retryAfterSeconds = 0L;
                log.info("Circuit breaker HALF_OPEN → CLOSED after successful probe");
            }
        } else if (current == State.CLOSED) {
            failureCount.set(0);
        }
    }

    /**
     * Records an infrastructure failure (Redis exception, malformed response).
     * <ul>
     *   <li>CLOSED: increments failure count; trips to OPEN when threshold is reached.</li>
     *   <li>HALF_OPEN → OPEN immediately (probe failed).</li>
     *   <li>OPEN: updates retryAfterSeconds and last failure time.</li>
     * </ul>
     *
     * @param retryAfterSec hint for how long callers should wait before retrying
     */
    public void onFailure(long retryAfterSec) {
        this.retryAfterSeconds = retryAfterSec > 0 ? retryAfterSec : resetTimeoutSeconds;
        lastFailureTimeMs.set(System.currentTimeMillis());

        State current = state.get();
        if (current == State.HALF_OPEN) {
            if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                log.warn("Circuit breaker HALF_OPEN → OPEN (probe failed)");
            }
            return;
        }

        int count = failureCount.incrementAndGet();
        if (current == State.CLOSED && count >= failureThreshold) {
            if (state.compareAndSet(State.CLOSED, State.OPEN)) {
                this.retryAfterSeconds = resetTimeoutSeconds;
                log.warn("Circuit breaker CLOSED → OPEN after {} consecutive infrastructure failures",
                        failureThreshold);
            }
        }
    }

    /**
     * Returns the number of seconds callers should wait before retrying.
     * Falls back to {@code resetTimeoutSeconds} when no hint is available.
     */
    public long getRetryAfterSeconds() {
        return retryAfterSeconds > 0 ? retryAfterSeconds : resetTimeoutSeconds;
    }

    /** Exposed for metrics/health-check endpoints. */
    public State getState() {
        return state.get();
    }

    public enum State {
        CLOSED, OPEN, HALF_OPEN
    }
}