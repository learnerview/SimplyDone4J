package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Keeps a running job's lease extended while its handler is working.
 *
 * <p>The lease is a deadline, not a promise. Without renewal a handler that runs longer
 * than the lease timeout is reclaimed by the lease reaper while it is still running, and
 * the job is executed a second time. Renewing every third of the lease timeout is often
 * enough that a single slow renewal cannot let the deadline pass and rare enough that the
 * renewal is not a meaningful share of Redis traffic.</p>
 */
final class LeaseRenewer {

    private static final Logger log = LoggerFactory.getLogger(LeaseRenewer.class);

    private final JobRepository jobRepo;
    private final ScheduledExecutorService scheduler;
    private final int leaseTimeoutSeconds;

    LeaseRenewer(JobRepository jobRepo, ScheduledExecutorService scheduler, int leaseTimeoutSeconds) {
        this.jobRepo = jobRepo;
        this.scheduler = scheduler;
        this.leaseTimeoutSeconds = leaseTimeoutSeconds;
    }

    /**
     * Starts renewal for the attempt that holds {@code job}'s lease.
     *
     * @return the renewal task, or {@code null} when the job has no lease or renewal is
     *         disabled.
     */
    ScheduledFuture<?> start(JobEntity job) {
        String leaseToken = job.getLeaseToken();
        if (leaseToken == null || leaseToken.isBlank() || leaseTimeoutSeconds <= 0) {
            return null;
        }
        long periodMs = Math.max(1000L, (leaseTimeoutSeconds * 1000L) / 3L);
        return scheduler.scheduleWithFixedDelay(
                () -> renew(job.getId(), leaseToken), periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

    private void renew(String jobId, String leaseToken) {
        try {
            Instant newExpiry = Instant.now().plusSeconds(leaseTimeoutSeconds);
            if (!jobRepo.extendLease(jobId, leaseToken, newExpiry)) {
                log.warn("Lease renewal rejected for job {} - the lease is no longer held", jobId);
            }
        } catch (Exception e) {
            // scheduleWithFixedDelay silently stops repeating a task that throws, so this
            // must never propagate. A transient Redis error would otherwise end the
            // renewal and let the reaper reclaim a job that is still running.
            log.warn("Lease renewal failed for job {}", jobId, e);
        }
    }
}
