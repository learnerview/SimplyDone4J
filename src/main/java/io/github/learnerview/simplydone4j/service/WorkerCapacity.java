package io.github.learnerview.simplydone4j.service;

import java.util.concurrent.Semaphore;

/**
 * Bounds the number of claimed-but-not-yet-finished jobs.
 *
 * <p>Claiming work in bulk without this bound moves the overload from Redis into the
 * executor queue: every claimed job has already been marked {@code RUNNING} and carries
 * a lease, so a deep backlog of claimed jobs is both invisible to the scheduler and
 * expensive to recover. The permit is released exactly once when execution finishes.</p>
 */
public final class WorkerCapacity {

    private final Semaphore permits;
    private final int maxInFlight;

    public WorkerCapacity(int maxInFlight) {
        this.maxInFlight = Math.max(1, maxInFlight);
        this.permits = new Semaphore(this.maxInFlight);
    }

    public boolean tryAcquire() {
        return permits.tryAcquire();
    }

    public void release() {
        permits.release();
    }

    public int availablePermits() {
        return permits.availablePermits();
    }

    public int inFlight() {
        return maxInFlight - permits.availablePermits();
    }

    public int maxInFlight() {
        return maxInFlight;
    }
}
