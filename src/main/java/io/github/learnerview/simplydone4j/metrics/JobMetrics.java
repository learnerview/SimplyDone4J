package io.github.learnerview.simplydone4j.metrics;

import io.github.learnerview.simplydone4j.model.JobPriority;

/**
 * Instrumentation seam for the engine.
 *
 * <p>Declared as an interface so the hot paths depend on a no-op constant rather than
 * on Micrometer types. Micrometer is an {@code optional} dependency of this starter,
 * so a consumer that does not pull it in must still be able to run the engine; every
 * call site stays a plain virtual dispatch on a singleton.</p>
 */
public interface JobMetrics {

    /** Terminal state a job attempt ended in. Kept coarse on purpose: this is a cardinality budget, not a ledger. */
    enum Outcome {
        SUCCESS,
        FAILED,
        TIMEOUT,
        DEAD_LETTER,
        /** The write was rejected because the lease moved on. The job is not lost; the other owner is writing it. */
        DISCARDED,
        /** Put back on the queue untouched because its unique key was already in use. Not a failure. */
        DEFERRED
    }

    /** All events no-op. Shared and stateless. */
    JobMetrics NOOP = new JobMetrics() {
        @Override public void recordSubmitted(JobPriority priority) { }
        @Override public void recordClaimed(int count) { }
        @Override public void recordCompleted(Outcome outcome, long durationMs) { }
        @Override public void recordFencedWriteRejected() { }
        @Override public void recordPoll(long durationMs, int claimed) { }
        @Override public void recordLeaseReaped() { }
        @Override public void recordOverlapped() { }
        @Override public boolean isEnabled() { return false; }
    };

    void recordSubmitted(JobPriority priority);

    void recordClaimed(int count);

    void recordCompleted(Outcome outcome, long durationMs);

    /**
     * A terminal write lost its fencing comparison. A steady trickle is normal after a
     * deploy or a partition; a sustained stream means leases are expiring while work is
     * still running, which is worth paging on.
     */
    void recordFencedWriteRejected();

    void recordPoll(long durationMs, int claimed);

    void recordLeaseReaped();

    /**
     * A job reached the executor and found its {@code uniqueKey} already held, so it was
     * put back on the queue. A little is normal for back-to-back scheduling; a lot means
     * the defer window is too short for the job's real duration.
     */
    void recordOverlapped();

    boolean isEnabled();
}
