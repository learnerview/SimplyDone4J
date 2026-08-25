package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.exception.RateLimitExceededException;
import io.github.learnerview.simplydone4j.service.RateLimiterService;
import io.github.learnerview.simplydone4j.service.RateLimiterStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RateLimiterServiceImpl implements RateLimiterService {
    private static final Logger log = LoggerFactory.getLogger(RateLimiterServiceImpl.class);

    private final RateLimiterStrategy primaryStrategy;
    private final RateLimiterStrategy fallbackStrategy;

    public RateLimiterServiceImpl(RateLimiterStrategy primaryStrategy, RateLimiterStrategy fallbackStrategy) {
        this.primaryStrategy = primaryStrategy;
        this.fallbackStrategy = fallbackStrategy;
    }

    @Override
    public void checkRateLimit(String producer) {
        try {
            primaryStrategy.checkRateLimit(producer);
        } catch (RateLimitExceededException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Primary rate limiter unavailable for producer {}, using fallback: {}", producer, e.getMessage());
            fallbackStrategy.checkRateLimit(producer);
        }
    }
}