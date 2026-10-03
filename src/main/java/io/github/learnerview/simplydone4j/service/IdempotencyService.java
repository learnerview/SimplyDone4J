package io.github.learnerview.simplydone4j.service;

import java.util.Optional;

public interface IdempotencyService {
    /**
     * Attempts to acquire an idempotency lock for the given producer and key.
     *
     * @param producer       the producer submitting the job
     * @param idempotencyKey the idempotency key for the job
     * @param jobId          the new job ID to store if the lock is acquired
     * @return Optional.empty() if the lock was successfully acquired. 
     *         Optional.of(existingJobId) if the lock was already held by another job.
     */
    Optional<String> acquireOrGetExisting(String producer, String idempotencyKey, String jobId);

    /**
     * Releases an idempotency lock, but only if it still points at {@code jobId}.
     *
     * <p>The lock and the job record have independent TTLs. If the job hash expires first
     * (a job past its retention window) while the idempotency key outlives it, the lock
     * keeps reporting a job id that no longer resolves. Without this escape hatch the
     * producer is permanently blocked from resubmitting that key.</p>
     *
     * @return {@code true} if the lock was released by this call.
     */
    boolean releaseIfOwnedBy(String producer, String idempotencyKey, String jobId);
}
