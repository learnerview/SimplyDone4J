package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class RateLimiterCircuitBreaker {
    private static final Logger log = LoggerFactory.getLogger(RateLimiterCircuitBreaker.class);

    private final SimplyDoneProperties config;
    private final int failureThreshold;
    private final long slowCallDurationThreshold;
    final long resetTimeoutSeconds;

    private final AtomicInteger failureCount = new AtomicInteger(0);
    private volatile State state = State.CLOSED;
    private volatile long lastFailureTime = 0;
    private volatile long retryAfterSeconds = 0L;
    private volatile boolean slowCallDetected = false;

    public RateLimiterCircuitBreaker(SimplyDoneProperties config) {
        this.config = config;
        this.failureThreshold = config.getRateLimit().getCircuitBreakerFailures() > 0 ?
                config.getRateLimit().getCircuitBreakerFailures() : 5;
        this.slowCallDurationThreshold = config.getRateLimit().getSlowCallDurationMs() > 0 ?
                config.getRateLimit().getSlowCallDurationMs() : 2000L;
        this.resetTimeoutSeconds = config.getRateLimit().getCircuitBreakerResetSeconds() > 0 ?
                config.getRateLimit().getCircuitBreakerResetSeconds() : 30L;
    }

    public void onScriptLoaded() {
        // Circuit breaker initialized successfully, start closed
        if (state != State.CLOSED) {
            state = State.CLOSED;
            failureCount.set(0);
            retryAfterSeconds = 0L;
        }
    }

    public void onScriptFailed() {
        // Circuit breaker could not initialize, start closed to allow fallback
        if (state != State.CLOSED) {
            state = State.CLOSED;
            failureCount.set(0);
            retryAfterSeconds = 0L;
        }
    }

    public boolean isOpen() {
        if (state == State.OPEN) {
            if (System.currentTimeMillis() - lastFailureTime >= TimeUnit.SECONDS.toMillis(resetTimeoutSeconds)) {
                state = State.HALF_OPEN;
                log.info("Circuit breaker transitioning from OPEN to HALF_OPEN");
            } else {
                return true;
            }
        }
        return false;
    }

    public void onSuccess() {
        if (state == State.HALF_OPEN) {
            state = State.CLOSED;
            failureCount.set(0);
            retryAfterSeconds = 0L;
            log.info("Circuit breaker closed after successful requests in HALF_OPEN state");
        } else if (state == State.CLOSED) {
            int currentCount = failureCount.incrementAndGet();
            if (currentCount >= failureThreshold) {
                state = State.OPEN;
                lastFailureTime = System.currentTimeMillis();
                retryAfterSeconds = resetTimeoutSeconds;
                log.warn("Circuit breaker opened after {} consecutive failures", failureThreshold);
            }
        }
    }

    public void onFailure(long retryAfterSeconds) {
        this.retryAfterSeconds = retryAfterSeconds;
        failureCount.incrementAndGet();
        lastFailureTime = System.currentTimeMillis();

        if (state == State.CLOSED) {
            int currentCount = failureCount.get();
            if (currentCount >= failureThreshold) {
                state = State.OPEN;
                this.retryAfterSeconds = resetTimeoutSeconds;
                log.warn("Circuit breaker opened after {} consecutive failures", failureThreshold);
            }
        } else if (state == State.HALF_OPEN) {
            state = State.OPEN;
            this.retryAfterSeconds = resetTimeoutSeconds;
            log.warn("Circuit breaker reopened in HALF_OPEN state after failure");
        }
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds > 0 ? retryAfterSeconds : 30L;
    }

    public boolean recordSlowCall() {
        slowCallDetected = true;
        if (state == State.CLOSED) {
            state = State.OPEN;
            lastFailureTime = System.currentTimeMillis();
            retryAfterSeconds = resetTimeoutSeconds;
            log.warn("Slow call detected, opening circuit breaker");
            return true;
        }
        return false;
    }

    enum State {
        CLOSED, OPEN, HALF_OPEN
    }
}