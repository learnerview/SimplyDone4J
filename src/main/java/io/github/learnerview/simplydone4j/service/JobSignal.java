package io.github.learnerview.simplydone4j.service;

import java.time.Duration;

/**
 * A wake-up signal for the scheduler.
 *
 * <p>A scheduler that only looks at the queues on a timer leaves every submitted job
 * waiting for the rest of the current interval: on the default settings that is up to a
 * second of latency on work that was already runnable. The signal turns that into an
 * event: whoever makes a job runnable says so, and the scheduler looks immediately.</p>
 *
 * <p>The signal is coalescing. Ten jobs submitted in a burst need one wake-up, not ten,
 * and the scheduler drains everything it can claim in one pass anyway.</p>
 */
public interface JobSignal {

    /**
     * Announces that there may be work to do. Never blocks.
     */
    void signal();

    /**
     * Waits for a signal, or for the timeout to pass.
     *
     * @return {@code true} if a signal arrived, {@code false} if the wait timed out
     */
    boolean await(Duration timeout) throws InterruptedException;
}
