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

    /**
     * Like {@link #saveIfLeaseHeld}, but additionally requires the persisted lease deadline
     * to be at or before {@code notAfter}.
     *
     * <p>The lease reaper lists expired {@code RUNNING} jobs and then fences its write with
     * the lease token. The token stays valid across a renewal ({@link #extendLease} only
     * moves the deadline), so a token check alone lets the reaper fail a lease that the
     * renewer extended between the listing and the write. Comparing the deadline as well
     * closes that window.</p>
     *
     * <p>Without deadline support a custom repository cannot distinguish an expired lease
     * from one that was renewed after the reaper listed it. Its default therefore refuses
     * a deadline-bound write; implementations that support lease recovery must override
     * this method and compare the persisted deadline atomically.</p>
     *
     * @return {@code true} if the write was applied; {@code false} if the lease was lost
     *         or renewed before the write.
     */
    default boolean saveIfLeaseHeldOverdue(JobEntity job, String expectedLeaseToken, java.time.Instant notAfter) {
        if (notAfter != null) {
            return false;
        }
        return saveIfLeaseHeld(job, expectedLeaseToken);
    }

    /**
     * Extends the visibility deadline of a job that is still {@code RUNNING} under
     * {@code expectedLeaseToken}.
     *
     * <p>Long-running handlers would otherwise outlive their lease and be reclaimed by the
     * reaper while still executing. Renewing only the deadline -- never the attempt count or
     * status -- keeps the running job visible to the reaper and lets a second executor
     * safely ignore a renewal whose lease has already moved on.</p>
     *
     * @return {@code true} if the lease still matched and the deadline was extended.
     */
    boolean extendLease(String jobId, String expectedLeaseToken, Instant visibleUntil);
}
