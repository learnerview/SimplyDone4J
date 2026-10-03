package io.github.learnerview.simplydone4j.service;

import io.github.learnerview.simplydone4j.entity.JobEntity;

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

    void logSuccess(JobEntity job, String message, long durationMs);
}
