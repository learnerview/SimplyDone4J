package io.github.learnerview.simplydone4j.repository;

import io.github.learnerview.simplydone4j.model.JobPriority;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface QueueRepository {
    void enqueue(String jobId, JobPriority priority, long scheduledAtEpochMs);

    /**
     * Atomically enqueues {@code jobId} only while the queues hold fewer than
     * {@code maxDepth} entries.
     *
     * <p>The previous admission check read the queue depth and then enqueued in a
     * separate round trip, so two submissions that both observed depth {@code maxDepth - 1}
     * could both be admitted and overshoot the cap. Here the depth test and the
     * {@code ZADD} happen inside one Redis script, which the server executes without
     * interleaving; a concurrent submit therefore sees the entry this one wrote.</p>
     *
     * <p>{@code maxDepth} is the <em>total</em> across all priority queues: a submission is
     * refused once the three queues together hold {@code maxDepth} jobs, whatever the
     * split between them. That is the number of jobs the workers are behind by, and it is
     * the same total the health threshold uses.</p>
     *
     * @return {@code true} if the job was enqueued; {@code false} if the queues were
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

    /**
     * Whether {@code jobId} currently has a ready-queue entry in the given priority queue.
     *
     * <p>The maintenance reconciler uses this to find {@code QUEUED} jobs whose queue
     * entry is gone. Removing a job from the queue and transitioning it to {@code RUNNING}
     * are two operations; a process that dies between them leaves a job that is queued by
     * status but invisible to the scheduler. Reconciliation puts such a job back.</p>
     */
    boolean contains(String jobId, JobPriority priority);

    /**
     * The depth of every priority queue.
     *
     * <p>Metrics, health and the depth sampler all want every queue's depth, and asking
     * for them one at a time costs one round trip per priority. An implementation that
     * can read them together should override this; the default reads them one by one.</p>
     *
     * @return the depth of each priority queue, keyed by priority.
     */
    default Map<JobPriority, Long> queueDepths() {
        Map<JobPriority, Long> depths = new EnumMap<>(JobPriority.class);
        for (JobPriority priority : JobPriority.values()) {
            depths.put(priority, queueSize(priority));
        }
        return depths;
    }

    void clearQueue(JobPriority priority);
    void clearAll();
}
