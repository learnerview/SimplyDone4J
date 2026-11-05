package io.github.learnerview.simplydone4j.service;

public interface WorkerMaintenanceService {
    void promoteRetries();
    void recoverExpiredLeases();

    /**
     * Re-queues {@code QUEUED} records that lost their queue entry after a crash.
     *
     * <p>Defaulted so custom maintenance implementations remain source-compatible; the
     * built-in implementation performs the reconciliation.</p>
     */
    default void reconcileOrphanedQueuedJobs() {
    }
}
