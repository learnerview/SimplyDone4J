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
}
