package io.github.learnerview.simplydone4j.repository;

import io.github.learnerview.simplydone4j.model.JobPriority;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

public interface QueueRepository {
    void enqueue(String jobId, JobPriority priority, long scheduledAtEpochMs);

    /**
     * Atomically enqueues {@code jobId} only while the target queue holds fewer than
     * {@code maxDepth} entries.
     *
     * <p>The previous admission check read the queue depth and then enqueued in a
     * separate round trip, so two submissions that both observed depth {@code maxDepth - 1}
     * could both be admitted and overshoot the cap. Here the depth test and the
     * {@code ZADD} happen inside one Redis script, which the server executes without
     * interleaving; a concurrent submit therefore sees the entry this one wrote.
     *
     * <p>The capacity is global across all priority queues. Redis Cluster requires the
     * configured queue keys to share a hash slot; a hash-tagged key prefix provides that
     * layout. Standalone Redis and Sentinel work without a hash tag.
     *
     * @return {@code true} if the job was enqueued; {@code false} if all queues were at
     *         capacity and nothing was written.
     */
    boolean enqueueIfUnderCapacity(String jobId, JobPriority priority, long scheduledAtEpochMs, long maxDepth);

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

    /**
     * Reports whether this job id is currently a member of its priority queue.
     *
     * <p>Used by orphan reconciliation. A worker removes an id from the queue before it
     * leases the record, so a crash in that window leaves a QUEUED record that no worker
     * will ever see again. The reconciler checks membership and re-adds missing ids.</p>
     *
     * <p>Returns {@code true} by default so pre-existing custom implementations keep their
     * previous behaviour and never create duplicate queue entries. Implementations that
     * provide reliable membership checks should override it.</p>
     */
    default boolean contains(String jobId, JobPriority priority) {
        return true;
    }

    /**
     * Returns the oldest scheduled timestamp currently present in a priority queue.
     * Implementations may return empty when the queue is empty.
     */
    default OptionalLong oldestScheduledAt(JobPriority priority) {
        return OptionalLong.empty();
    }
    void clearQueue(JobPriority priority);
    void clearAll();
}
