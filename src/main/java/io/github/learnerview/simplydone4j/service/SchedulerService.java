package io.github.learnerview.simplydone4j.service;

public interface SchedulerService {
    void poll();

    /**
     * Polls once and reports how many jobs were handed to the executor.
     *
     * <p>The count lets the scheduling loop shorten its wait while there is still work
     * to claim. Implementations that cannot report a count may keep the default.</p>
     */
    default int pollOnce() {
        poll();
        return 0;
    }
}
