package io.github.learnerview.simplydone4j.service;

public interface WorkerMaintenanceService {
    void promoteRetries();
    void recoverExpiredLeases();

    /**
     * Re-enqueues {@code QUEUED} jobs whose ready-queue entry is missing.
     *
     * <p>A worker removing a job from the queue and then claiming it are separate Redis
     * operations. A process that dies between them leaves the job {@code QUEUED} with no
     * queue entry: the scheduler only lists the queue, so nothing will ever run it and
     * nothing reports it as lost. This pass is the recovery path for that window; it is
     * idempotent, so a job that still has its entry is left untouched.</p>
     *
     * @return the number of jobs that were put back on a queue.
     */
    int reconcileQueuedJobs();

    /**
     * Removes terminal status-index members whose jobs are outside the retention window.
     *
     * <p>The indexes are shared ZSETs, so they cannot use a per-key TTL without either
     * keeping every old member alive or deleting the whole index during a quiet period.
     */
    void pruneTerminalIndexes();
}
