package io.github.learnerview.simplydone4j.repository;

import io.github.learnerview.simplydone4j.model.JobPriority;

import java.util.List;
import java.util.Optional;

public interface QueueRepository {
    void enqueue(String jobId, JobPriority priority, long scheduledAtEpochMs);

    Optional<String> claimNextReady(JobPriority priority);

    /**
     * Atomically claims up to {@code limit} ready job ids from one priority queue.
     *
     * <p>Claiming in batches is what makes throughput scale with the worker pool instead of
     * the poll interval: a scheduler that removes a single job per tick is capped at
     * {@code 1000 / pollingIntervalMs} jobs per second per instance, no matter how many
     * threads are available to run them.</p>
     *
     * @return the claimed ids, oldest scheduled first. Empty if nothing was ready or if a
     *         competing worker won the race.
     */
    List<String> claimReady(JobPriority priority, int limit);

    void remove(String jobId, JobPriority priority);
    long queueSize(JobPriority priority);
    void clearQueue(JobPriority priority);
    void clearAll();
}
