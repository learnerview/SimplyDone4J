package io.github.learnerview.simplydone4j.service;

import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.model.JobErrorCode;

public interface RetryService {

    /**
     * Records a failure without checking the lease. Use only from paths that are not
     * racing a lease expiry, such as administrative transitions.
     */
    String handleFailure(JobEntity job, String errorMessage, long durationMs);

    /**
     * Records a failure only if {@code job} still holds {@code expectedLeaseToken},
     * checking and writing atomically in the store.
     *
     * <p>This is the safe form for the executor and the lease reaper. Both can observe a
     * lease that a third party has already reclaimed, and an unfenced write from either
     * would double-count the attempt or resurrect a job that another worker is running.</p>
     *
     * @return the resulting status ({@code RETRY_SCHEDULED} or {@code DLQ}), or {@code null}
     *         if the lease was lost and the write was discarded.
     */
    String handleFailureIfLeaseHeld(JobEntity job, String expectedLeaseToken,
                                    String errorMessage, long durationMs);

    /**
     * Records a failure with the code that says why.
     *
     * <p>The code is what a program branches on; the message is for a human. A failure
     * recorded without a code keeps the older behaviour, so an application that never
     * learns the codes still works.</p>
     *
     * @return the resulting status ({@code RETRY_SCHEDULED} or {@code DLQ}), or {@code null}
     *         if the lease was lost and the write was discarded.
     */
    default String handleFailureIfLeaseHeld(JobEntity job, String expectedLeaseToken,
                                            JobErrorCode errorCode, String errorMessage, long durationMs) {
        return handleFailureIfLeaseHeld(job, expectedLeaseToken, errorMessage, durationMs);
    }

    /**
     * Records a terminal failure: one that retrying cannot fix.
     *
     * <p>An unknown job type is the canonical case. No handler exists for it, so every
     * attempt would fail in exactly the same way and the retry budget would be spent to
     * reach the same conclusion. The job goes straight to {@code FAILED} instead, which is
     * a terminal state: it is indexed, it is retained, and it can be inspected and
     * re-driven deliberately.</p>
     *
     * @return the resulting status ({@code FAILED}), or {@code null} if the lease was lost
     *         and the write was discarded.
     */
    String handleTerminalFailureIfLeaseHeld(JobEntity job, String expectedLeaseToken,
                                            JobErrorCode errorCode, String errorMessage, long durationMs);

    void logSuccess(JobEntity job, String message, long durationMs);
}
