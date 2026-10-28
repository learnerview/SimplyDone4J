package io.github.learnerview.simplydone4j.service;

import io.github.learnerview.simplydone4j.entity.JobEntity;

import java.util.List;

/**
 * Operator-facing recovery for jobs parked in the dead-letter state.
 *
 * <p>A job reaches the DLQ when it has exhausted its attempt budget. That is a
 * deliberate stop, not a discard: the record, the payload and the execution log are all
 * still there, and the usual reason to land in the DLQ is a dependency outage that has
 * since been fixed. Without a requeue path the only options are to raise
 * {@code maxAttempts} and resubmit by hand, or to delete the job and lose its
 * idempotency history.</p>
 *
 * <p>Auto-configured. Override by registering your own {@code DeadLetterService} bean.</p>
 */
public interface DeadLetterService {

    /**
     * Returns the dead-lettered jobs, newest first, capped at {@code limit}.
     *
     * @param limit maximum number of records to return; must be positive
     */
    List<JobEntity> listDeadLettered(int limit);

    /**
     * Puts a single dead-lettered job back on the queue.
     *
     * @return the requeued job
     * @throws io.github.learnerview.simplydone4j.exception.JobNotFoundException  if no
     *         job with that id exists
     * @throws IllegalStateException if the job is not currently in the DLQ, or if the
     *         requeue would leave it with no attempts left
     */
    JobEntity requeue(String jobId);

    /**
     * Requeues up to {@code limit} dead-lettered jobs.
     *
     * <p>Each job is requeued independently: one job that cannot be moved on does not
     * stop the rest, and the returned list contains only what actually moved.</p>
     *
     * @return the jobs that were successfully requeued
     */
    List<JobEntity> requeueAll(int limit);
}
