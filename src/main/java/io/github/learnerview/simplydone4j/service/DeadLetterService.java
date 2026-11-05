package io.github.learnerview.simplydone4j.service;

import io.github.learnerview.simplydone4j.dto.JobResponse;

import java.util.List;

/**
 * Operational access to jobs that exhausted their retry budget.
 *
 * <p>Dead-lettered jobs are ordinary {@code DLQ} records in the status index. This
 * service provides the supported recovery path: inspect the oldest entries, replay one
 * with a fresh attempt budget, or permanently discard it.</p>
 */
public interface DeadLetterService {

    int DEFAULT_LIST_LIMIT = 100;

    /**
     * Returns up to {@code limit} dead-lettered jobs, oldest first.
     */
    List<JobResponse> list(int limit);

    default List<JobResponse> list() {
        return list(DEFAULT_LIST_LIMIT);
    }

    /**
     * Returns the number of live dead-lettered jobs.
     */
    long count();

    /**
     * Atomically resets a dead-lettered job and returns it to its priority queue.
     *
     * <p>The attempt counter, result, timestamps and lease fields are cleared. The job
     * keeps its original {@code maxAttempts} budget. Replay is rejected if the job has
     * already left {@code DLQ} or the queue is at capacity.</p>
     */
    JobResponse replay(String jobId);

    /**
     * Permanently removes a dead-lettered job.
     */
    void discard(String jobId);
}
