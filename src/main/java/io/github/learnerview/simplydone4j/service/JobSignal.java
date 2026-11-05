package io.github.learnerview.simplydone4j.service;

/**
 * Wakes the scheduler when work becomes available.
 *
 * <p>A fixed one-second poll puts a hard ceiling on pickup latency and burns Redis
 * round-trips while the queue is idle. The signal carries the "something changed"
 * hint; the scheduler still falls back to a bounded wait so a missed signal cannot
 * stall the queue forever.</p>
 */
public interface JobSignal {

    void signal();

    /**
     * Waits for a signal or the timeout, whichever comes first.
     *
     * @return {@code true} if a signal arrived, {@code false} on timeout.
     */
    boolean await(long timeoutMs) throws InterruptedException;

    JobSignal NOOP = new JobSignal() {
        @Override
        public void signal() {
        }

        @Override
        public boolean await(long timeoutMs) throws InterruptedException {
            Thread.sleep(timeoutMs);
            return false;
        }
    };
}
