package io.github.learnerview.simplydone4j.service;

import io.github.learnerview.simplydone4j.exception.RateLimitExceededException;

public interface RateLimiterStrategy {
    void checkRateLimit(String producer) throws RateLimitExceededException;
}
