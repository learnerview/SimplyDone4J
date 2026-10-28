package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.service.RetryPolicy;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff with positive-and-negative jitter.
 *
 * <p>Delays are computed in milliseconds so the same arithmetic serves job retries
 * (configured in seconds) and webhook delivery (configured in milliseconds). Both
 * spread their retries, but for the same reason: a fleet of jobs that failed together
 * — a bad deploy, an expired credential, a downstream outage — must not replay in
 * lockstep against the dependency that is trying to recover.</p>
 */
public class ExponentialBackoffRetryPolicy implements RetryPolicy {

    private final long initialDelayMillis;
    private final double backoffMultiplier;
    private final long maxDelayMillis;
    private final double jitterFactor;

    public ExponentialBackoffRetryPolicy(SimplyDoneProperties config) {
        this(config.getRetry().getInitialDelaySeconds() * 1000L,
                config.getRetry().getBackoffMultiplier(),
                config.getRetry().getMaxDelaySeconds() * 1000L,
                config.getRetry().getJitterFactor());
    }

    /**
     * Package-private so the webhook delivery can reuse this policy with its own
     * millisecond configuration without adding a second public knob to the API.
     */
    ExponentialBackoffRetryPolicy(long initialDelayMillis, double backoffMultiplier,
                                  long maxDelayMillis, double jitterFactor) {
        this.initialDelayMillis = Math.max(0L, initialDelayMillis);
        this.backoffMultiplier = backoffMultiplier > 0 ? backoffMultiplier : 1.0;
        this.maxDelayMillis = Math.max(0L, maxDelayMillis);
        this.jitterFactor = Math.max(0.0, jitterFactor);
    }

    @Override
    public long calculateDelayMs(int attempt) {
        double exponential = initialDelayMillis
                * Math.pow(backoffMultiplier, Math.max(0, attempt));

        // Cap in floating point before narrowing to long. Math.pow on a large attempt count
        // overflows a double into Infinity, and casting that straight to long saturates to
        // Long.MAX_VALUE, which would park the job until the heat death of the universe.
        double capped = Math.min(exponential, (double) maxDelayMillis);

        // Randomise within +/- jitterFactor, then clamp again: the cap is an upper bound
        // on the delay, so jitter must not push a retry past it either.
        double jittered = capped * (1.0 + jitterFactor * (ThreadLocalRandom.current().nextDouble() * 2 - 1));
        double bounded = Math.max(0.0, Math.min(jittered, (double) maxDelayMillis));

        return (long) bounded;
    }
}
