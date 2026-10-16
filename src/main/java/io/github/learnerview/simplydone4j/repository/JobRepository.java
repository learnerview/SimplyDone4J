package io.github.learnerview.simplydone4j.repository;

import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.model.JobStatus;


import java.util.Optional;

public interface JobRepository extends JobWorkerRepository, JobQueryRepository {
    void save(JobEntity job);
    Optional<JobEntity> findByProducerAndIdempotencyKey(String producer, String idempotencyKey);

    /**
     * Removes a job record and every index entry that points at it.
     *
     * <p>Used to compensate a submission whose job was saved but could not be enqueued:
     * without it the record would read {@code QUEUED} forever with no queue entry to hand
     * it to a worker.
     */
    void delete(String jobId);

    /**
     * Atomically moves a job from {@code expected} to {@code target} only if the stored
     * status is still {@code expected}.
     *
     * <p>A read-check-write in application memory lets a worker claim race a cancel: the
     * cancel reads {@code QUEUED}, the worker commits {@code RUNNING}, then the cancel
     * write overwrites it to {@code CANCELLED} and a job that is already executing is
     * reported cancelled. Comparing and writing under a single Redis transaction makes the
     * store itself reject the loser.
     *
     * @return {@code true} if the transition was applied; {@code false} if the job was
     *         missing or its status had already moved on.
     */
    boolean transitionStatusIfCurrent(String jobId, JobStatus expected, JobStatus target);
}
