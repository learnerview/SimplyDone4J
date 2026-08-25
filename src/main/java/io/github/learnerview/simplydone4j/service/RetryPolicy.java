package io.github.learnerview.simplydone4j.service;

public interface RetryPolicy {
    /**
     * Determines the delay in milliseconds for the next retry based on the attempt count.
     *
     * @param attempt the current attempt count (0-based)
     * @return the delay in milliseconds
     */
    long calculateDelayMs(int attempt);
}
