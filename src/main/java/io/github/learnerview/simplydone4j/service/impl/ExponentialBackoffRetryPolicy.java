package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.service.RetryPolicy;

public class ExponentialBackoffRetryPolicy implements RetryPolicy {
    private final int initialDelaySeconds;
    private final double backoffMultiplier;

    public ExponentialBackoffRetryPolicy(SimplyDoneProperties config) {
        this.initialDelaySeconds = config.getRetry().getInitialDelaySeconds();
        this.backoffMultiplier = config.getRetry().getBackoffMultiplier();
    }

    @Override
    public long calculateDelayMs(int attempt) {
        return (long) (initialDelaySeconds * 1000L * Math.pow(backoffMultiplier, attempt));
    }
}
