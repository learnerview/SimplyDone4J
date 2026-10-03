package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.service.RetryPolicy;

import java.util.concurrent.ThreadLocalRandom;

public class ExponentialBackoffRetryPolicy implements RetryPolicy {

    private final long initialDelaySeconds;
    private final double backoffMultiplier;
    private final long maxDelaySeconds;
    private final double jitterFactor;

    public ExponentialBackoffRetryPolicy(SimplyDoneProperties config) {
        this.initialDelaySeconds = config.getRetry().getInitialDelaySeconds();
        this.backoffMultiplier = config.getRetry().getBackoffMultiplier();
        this.maxDelaySeconds = config.getRetry().getMaxDelaySeconds();
        this.jitterFactor = config.getRetry().getJitterFactor();
    }

    @Override
    public long calculateDelayMs(int attempt) {
        double exponential = initialDelaySeconds * Math.pow(backoffMultiplier, Math.max(0, attempt));

        // Cap in floating point before narrowing to long. Math.pow on a large attempt count
        // overflows a double into Infinity, and casting that straight to long saturates to
        // Long.MAX_VALUE, which would park the job until the heat death of the universe.
        double capped = Math.min(exponential, (double) maxDelaySeconds);

        // Randomise within +/- jitterFactor so jobs that failed together -- a bad deploy, an
        // expired credential, a downstream outage -- do not all retry in lockstep and
        // reproduce the same failure against the recovering dependency.
        double jittered = capped * (1.0 + jitterFactor * (ThreadLocalRandom.current().nextDouble() * 2 - 1));
        double bounded = Math.max(0.0, Math.min(jittered, (double) maxDelaySeconds));

        return (long) (bounded * 1000.0);
    }
}
