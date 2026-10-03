package io.github.learnerview.simplydone4j.repository;

import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.model.JobStatus;

import java.time.Instant;
import java.util.List;

public interface JobWorkerRepository {
    List<JobEntity> findReadyToRun(JobStatus status, Instant before, int limit);
    int claimForExecution(String jobId, String leaseToken, String workerId, Instant visibleUntil, Instant now, JobStatus fromStatus, JobStatus toStatus);

    /**
     * Writes {@code job} only if the persisted lease still matches {@code expectedLeaseToken},
     * and does so atomically.
     *
     * <p>This is the fencing guarantee. Checking the token in application memory and then
     * writing is not enough: between the check and the write the lease can be reaped and
     * handed to another worker, and the stale write lands anyway. Performing the comparison
     * and the write under a single {@code WATCH}/{@code MULTI}/{@code EXEC} means the store
     * itself rejects a superseded writer.</p>
     *
     * @return {@code true} if the write was applied; {@code false} if the lease was no
     *         longer held and the write was discarded.
     */
    boolean saveIfLeaseHeld(JobEntity job, String expectedLeaseToken);
}
