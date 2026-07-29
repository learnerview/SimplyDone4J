package io.github.learnerview.simplydone4j.service;

@FunctionalInterface
public interface RateLimiterService {
    void checkRateLimit(String producer);
}
