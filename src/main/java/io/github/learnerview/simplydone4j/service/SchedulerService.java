package io.github.learnerview.simplydone4j.service;

import java.time.Duration;

/**
 * The component that moves jobs off the queues and onto the workers.
 */
@FunctionalInterface
public interface SchedulerService {

    /**
     * Claims and dispatches whatever is ready.
     */
    void poll();

    /**
     * How long to wait before the next poll.
     *
     * <p>A scheduler that always waits the same amount is either too eager when it is
     * idle or too slow when it is busy. Implementations that know how much work they
     * found return a shorter delay while there is more to do.</p>
     */
    default Duration nextPollDelay() {
        return Duration.ofMillis(1000L);
    }
}
