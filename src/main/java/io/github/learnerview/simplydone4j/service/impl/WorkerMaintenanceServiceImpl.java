package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.github.learnerview.simplydone4j.service.JobSignal;
import io.github.learnerview.simplydone4j.service.RetryService;
import io.github.learnerview.simplydone4j.service.WorkerMaintenanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

public final class WorkerMaintenanceServiceImpl implements WorkerMaintenanceService {
    private static final Logger log = LoggerFactory.getLogger(WorkerMaintenanceServiceImpl.class);

    private final JobRepository jobRepo;
    private final QueueRepository queueRepo;
    private final RetryService retryService;
    private final JobSignal signal;
    private final long maxQueueDepth;
    private final int orphanScanLimit;
    private final long orphanGraceMs;
    private final int retryScanLimit;
    private final int leaseScanLimit;

    public WorkerMaintenanceServiceImpl(JobRepository jobRepo, QueueRepository queueRepo,
                                        RetryService retryService) {
        this(jobRepo, queueRepo, retryService, null, JobSignal.NOOP);
    }

    public WorkerMaintenanceServiceImpl(JobRepository jobRepo, QueueRepository queueRepo,
                                        RetryService retryService,
                                        SimplyDoneProperties config) {
        this(jobRepo, queueRepo, retryService, config, JobSignal.NOOP);
    }

    public WorkerMaintenanceServiceImpl(JobRepository jobRepo, QueueRepository queueRepo,
                                        RetryService retryService,
                                        SimplyDoneProperties config, JobSignal signal) {
        this.jobRepo = jobRepo;
        this.queueRepo = queueRepo;
        this.retryService = retryService;
        this.signal = signal == null ? JobSignal.NOOP : signal;
        this.maxQueueDepth = config == null
                ? Long.MAX_VALUE
                : Math.max(1L, config.getQueue().getMaxDepth());
        this.orphanScanLimit = config == null
                ? 200
                : Math.max(1, config.getWorker().getOrphanReconcileScanLimit());
        this.orphanGraceMs = config == null
                ? 5_000L
                : Math.max(0L, config.getWorker().getOrphanGraceMs());
        this.retryScanLimit = config == null
                ? 100
                : Math.max(1, config.getWorker().getRetryPromoterScanLimit());
        this.leaseScanLimit = config == null
                ? 100
                : Math.max(1, config.getWorker().getLeaseReaperScanLimit());
    }

    @Override
    public void promoteRetries() {
        try {
            Instant now = Instant.now();
            List<JobEntity> due = jobRepo.findReadyToRun(
                    JobStatus.RETRY_SCHEDULED, now, retryScanLimit);

            boolean promoted = false;
            for (JobEntity job : due) {
                try {
                    // Compare-and-set against RETRY_SCHEDULED. Two promoters can list the same
                    // due retry; without this, the loser overwrites a job that the winner's
                    // scheduler has already claimed and leased, resurrecting a RUNNING job as
                    // QUEUED and deleting its live lease fields.
                    if (!jobRepo.transitionStatusIfCurrent(job.getId(),
                            JobStatus.RETRY_SCHEDULED, JobStatus.QUEUED)) {
                        continue;
                    }
                    boolean admitted;
                    try {
                        admitted = queueRepo.enqueueIfUnderCapacity(job.getId(), job.getPriority(),
                                job.getNextRunAt() != null
                                        ? Math.max(0L, job.getNextRunAt().toEpochMilli())
                                        : now.toEpochMilli(),
                                maxQueueDepth);
                    } catch (RuntimeException enqueueFailure) {
                        // The status CAS and queue write cannot share one transaction.
                        // Restore RETRY_SCHEDULED when the queue write definitely failed;
                        // if Redis reported an ambiguous outcome, ZADD remains idempotent on
                        // the next promotion and the CAS prevents a live job from being moved
                        // backwards.
                        restoreRetryScheduled(job, enqueueFailure);
                        continue;
                    }
                    if (!admitted) {
                        // Do not promote into a full queue: leaving the job QUEUED without a
                        // queue entry strands it, because nothing scans QUEUED status. Put the
                        // retry back into RETRY_SCHEDULED and try again next interval.
                        restoreRetryScheduled(job, null);
                        log.warn("Retry promotion for job {} deferred: queue is at max-depth {}",
                                job.getId(), maxQueueDepth);
                        continue;
                    }
                    promoted = true;
                } catch (RuntimeException e) {
                    log.warn("Could not promote retry for job {}; continuing with the batch",
                            job.getId(), e);
                }
            }
            if (promoted) signal.signal();
        } catch (Exception e) {
            log.error("Retry promoter failed", e);
        }
    }

    private void restoreRetryScheduled(JobEntity job, RuntimeException failure) {
        boolean restored = jobRepo.transitionStatusIfCurrent(
                job.getId(), JobStatus.QUEUED, JobStatus.RETRY_SCHEDULED);
        if (!restored) {
            log.warn("Retry promotion for job {} could not be restored and its state "
                    + "changed concurrently; the next recovery must reconcile it",
                    job.getId(), failure);
        } else if (failure != null) {
            log.warn("Retry promotion enqueue failed for job {}; restored RETRY_SCHEDULED",
                    job.getId(), failure);
        }
    }

    @Override
    public void recoverExpiredLeases() {
        try {
            Instant now = Instant.now();
            List<JobEntity> expired = jobRepo.findReadyToRun(JobStatus.RUNNING, now, leaseScanLimit);

            for (JobEntity job : expired) {
                try {
                    JobEntity current = jobRepo.findById(job.getId()).orElse(null);
                    if (current == null || current.getStatus() != JobStatus.RUNNING) continue;

                    String leaseToken = current.getLeaseToken();
                    if (leaseToken == null) {
                        log.warn("Job {} is RUNNING without a lease token, skipping recovery", job.getId());
                        continue;
                    }

                    // The lease is re-checked inside the write, atomically, and the persisted
                    // visibility deadline must not have moved past this listing's "now". A
                    // renewer that extended the lease after this job was listed keeps the job.
                    String outcome = retryService.handleFailureIfLeaseHeldOverdue(
                            current, leaseToken, now, "Worker lease expired", 0L);

                    if (outcome == null) {
                        log.info("Job {} was not recovered: the lease was lost or renewed before the write",
                                job.getId());
                        continue;
                    }
                    log.warn("Recovered expired lease for job {} -> {}", job.getId(), outcome);
                } catch (RuntimeException e) {
                    log.warn("Could not recover expired lease for job {}; continuing with the batch",
                            job.getId(), e);
                }
            }
        } catch (Exception e) {
            log.error("Lease reaper failed", e);
        }
    }

    /**
     * Re-queues QUEUED records whose claim attempt died before the lease was written.
     *
     * <p>{@code claimReady} removes ids from the priority ZSET, then the scheduler leases
     * each one in a separate write. A process crash between those two steps leaves a job
     * that reads QUEUED but has no queue entry and no lease, so neither the scheduler nor
     * the lease reaper will ever look at it again. This scan closes that gap.</p>
     *
     * <p>A grace period avoids racing a submission that has persisted its record but has
     * not yet enqueued it. Re-adding a duplicate member is harmless because the ZSET is
     * keyed by job id; the extra member is removed by the next claim and its
     * {@code claimForExecution} loses to the worker that already owns the job.</p>
     */
    public void reconcileOrphanedQueuedJobs() {
        try {
            Instant now = Instant.now();
            Instant cutoff = now.minusMillis(orphanGraceMs);
            List<JobEntity> queued = jobRepo.findReadyToRun(JobStatus.QUEUED, now, orphanScanLimit);
            boolean requeued = false;
            for (JobEntity job : queued) {
                if (job.getUpdatedAt() != null && job.getUpdatedAt().isAfter(cutoff)) {
                    continue;
                }
                try {
                    if (queueRepo.contains(job.getId(), job.getPriority())) {
                        continue;
                    }
                    long nowMs = now.toEpochMilli();
                    boolean admitted = queueRepo.enqueueIfUnderCapacity(job.getId(), job.getPriority(),
                            job.getNextRunAt() != null
                                    ? Math.max(0L, job.getNextRunAt().toEpochMilli())
                                    : nowMs,
                            maxQueueDepth);
                    if (admitted) {
                        requeued = true;
                        log.warn("Re-queued orphaned QUEUED job {} that had no queue entry", job.getId());
                    }
                } catch (RuntimeException e) {
                    log.warn("Could not inspect or re-queue orphaned job {}", job.getId(), e);
                }
            }
            if (requeued) signal.signal();
        } catch (Exception e) {
            log.error("Orphan reconciler failed", e);
        }
    }
}
