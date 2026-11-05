package io.github.learnerview.simplydone4j.repository;

import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.model.JobStatus;


import java.time.Instant;
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
     * Writes the complete job only if the stored record still has {@code expectedStatus}.
     *
     * <p>Used by dead-letter replay: the reset fields and the {@code QUEUED} status must
     * become visible together. A status-only compare-and-set followed by a second write
     * leaves a crash window in which an orphaned {@code QUEUED} record still carries the
     * old attempt count and result.</p>
     *
     * <p>The default implementation is a read-check-write for custom repositories. The
     * built-in Redis implementation performs the compare and write in one transaction.</p>
     *
     * @return {@code true} if the record existed, still had {@code expectedStatus}, and
     *         the write was applied.
     */
    default boolean saveIfStatus(JobEntity job, JobStatus expectedStatus) {
        if (job == null || job.getId() == null || expectedStatus == null) {
            return false;
        }
        Optional<JobEntity> current = findById(job.getId());
        if (current.isEmpty() || current.get().getStatus() != expectedStatus) {
            return false;
        }
        save(job);
        return true;
    }

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
     * <p>The default keeps existing custom repositories source-compatible by performing a
     * read-check-write. Implementations that can provide an atomic compare-and-set should
     * override it; only such an implementation closes the cancel/claim race described
     * above.</p>
     *
     * @return {@code true} if the transition was applied; {@code false} if the job was
     *         missing or its status had already moved on.
     */
    default boolean transitionStatusIfCurrent(String jobId, JobStatus expected, JobStatus target) {
        if (expected == null || target == null) {
            return false;
        }
        Optional<JobEntity> current = findById(jobId);
        if (current.isEmpty() || current.get().getStatus() != expected) {
            return false;
        }
        JobEntity job = current.get();
        job.setStatus(target);
        job.setUpdatedAt(Instant.now());
        save(job);
        return true;
    }
}
