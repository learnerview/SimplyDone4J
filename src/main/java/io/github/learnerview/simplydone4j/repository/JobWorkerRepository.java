package io.github.learnerview.simplydone4j.repository;

import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.model.CancelOutcome;
import io.github.learnerview.simplydone4j.model.JobStatus;

import java.time.Instant;
import java.util.List;

public interface JobWorkerRepository {
    /**
     * Returns up to {@code limit} jobs in {@code status} whose index score is at or before
     * {@code before}, starting at {@code offset}.
     *
     * <p>The offset exists so a bounded maintenance pass can rotate through a large queue
     * instead of examining the same oldest page forever.</p>
     */
    List<JobEntity> findReadyToRun(JobStatus status, Instant before, int limit, int offset);

    default List<JobEntity> findReadyToRun(JobStatus status, Instant before, int limit) {
        return findReadyToRun(status, before, limit, 0);
    }

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
     * Pushes the lease expiry of a running job further out, but only if the job still
     * holds {@code expectedLeaseToken}.
     *
     * <p>A lease is a deadline, not a promise that the work finishes before it. Without
     * renewal, a handler that runs longer than the lease timeout is reclaimed by the lease
     * reaper while it is still running: the job is claimed a second time, and both attempts
     * execute the same side effects. The renewal is fenced by the same token that
     * authorises a terminal write, so a worker that already lost the lease cannot extend
     * somebody else's.</p>
     *
     * @param newVisibleUntil the new lease expiry
     * @return {@code true} if the lease was extended; {@code false} if the job no longer
     *         holds the token (it was reclaimed, finished or cancelled).
     */
    boolean extendLease(String jobId, String expectedLeaseToken, Instant newVisibleUntil);

    /**
     * Records how far a running job has got.
     *
     * <p>Progress is informational, so this is deliberately not fenced by the lease: a
     * stale progress value from a worker that already lost the lease is harmless, and
     * fencing it would make the write fail exactly when the job is in trouble. The write
     * touches two hash fields and nothing else, so it cannot disturb the job's state.</p>
     *
     * @param percent how far the handler reported, 0 to 100
     * @param message what the handler last reported, may be {@code null}
     * @return {@code true} if the progress was stored
     */
    boolean updateProgress(String jobId, double percent, String message);

    /**
     * Asks for a job to be cancelled, atomically.
     *
     * <p>A job that is still waiting can be cancelled outright: the status moves to
     * {@code CANCELLED} and the job never runs. A job that is already running cannot be
     * un-run, so the request is recorded instead and the worker that owns the job is
     * expected to stop. Doing both in one atomic step is what keeps the two apart from
     * the caller: a caller that cannot tell them apart would report a cancelled job that
     * is still executing.</p>
     *
     * @param jobId the job to cancel
     * @param cancelledBy who asked for it, for the audit trail
     * @return what the request did
     */
    CancelOutcome requestCancellation(String jobId, String cancelledBy);

    /**
     * Whether a cancellation has been requested for this job.
     *
     * <p>The worker polls this while the handler runs. It is a single field read, not a
     * job read, because it happens once a second for every running job.</p>
     */
    boolean isCancellationRequested(String jobId);
}
