package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.service.RetryPolicy;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

public class ExponentialBackoffRetryPolicy implements RetryPolicy {
    private static final long MAX_DELAY_MS = Duration.ofDays(3650).toMillis();

    private final int initialDelaySeconds;
    private final double backoffMultiplier;
    private final double jitterFactor;

    public ExponentialBackoffRetryPolicy(SimplyDoneProperties config) {
        this.initialDelaySeconds = Math.max(0, config.getRetry().getInitialDelaySeconds());
        double configuredMultiplier = config.getRetry().getBackoffMultiplier();
        this.backoffMultiplier = Double.isFinite(configuredMultiplier) && configuredMultiplier >= 0
                ? configuredMultiplier : 1.0;
        double configuredJitter = config.getRetry().getJitterFactor();
        this.jitterFactor = Double.isFinite(configuredJitter)
                ? Math.max(0.0, Math.min(1.0, configuredJitter)) : 0.0;
    }

    @Override
    public long calculateDelayMs(int attempt) {
        double raw = initialDelaySeconds * 1000.0
                * Math.pow(backoffMultiplier, Math.max(0, attempt));
        if (!Double.isFinite(raw) || raw >= MAX_DELAY_MS) {
            return MAX_DELAY_MS;
        }
        long base = Math.max(0L, (long) raw);
        if (base == 0L || jitterFactor == 0.0) {
            return base;
        }

        // Spread retries by +/- jitterFactor around the deterministic backoff. Without this,
        // every job that failed in the same burst wakes at the same instant and stampedes
        // Redis and the external dependency that caused the failure.
        long spread = (long) Math.floor(base * jitterFactor);
        long low = Math.max(0L, base - spread);
        long high = Math.min(MAX_DELAY_MS, base + spread);
        return high <= low ? base : ThreadLocalRandom.current().nextLong(low, high + 1);
    }
}
