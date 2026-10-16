package io.github.learnerview.simplydone4j.repository;

import io.github.learnerview.simplydone4j.model.JobPriority;

import java.util.List;
import java.util.Optional;

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
     * <p>The capacity is enforced on the <em>target</em> queue rather than the sum across
     * all priority queues. A sum would require a script keyed on every queue, and Redis
     * Cluster rejects a script whose keys do not share a hash slot, so a per-target-queue
     * check is the atomic option that holds across standalone, Sentinel and Cluster.
     *
     * @return {@code true} if the job was enqueued; {@code false} if the target queue was
     *         already at capacity and nothing was written.
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
    void clearQueue(JobPriority priority);
    void clearAll();
}
