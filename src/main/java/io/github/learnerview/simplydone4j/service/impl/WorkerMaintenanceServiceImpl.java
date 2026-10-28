package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.event.JobEvent;
import io.github.learnerview.simplydone4j.event.JobEventData;
import io.github.learnerview.simplydone4j.event.JobEventPublisher;
import io.github.learnerview.simplydone4j.metrics.JobMetrics;
import io.github.learnerview.simplydone4j.model.JobErrorCode;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.github.learnerview.simplydone4j.service.RetryService;
import io.github.learnerview.simplydone4j.service.WorkerMaintenanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class WorkerMaintenanceServiceImpl implements WorkerMaintenanceService {
    private static final Logger log = LoggerFactory.getLogger(WorkerMaintenanceServiceImpl.class);

    /**
     * How many queued jobs one reconciliation pass examines. Bounded so the pass stays a
     * small, predictable amount of work even when the queue is deep.
     */
    private static final int RECONCILE_BATCH = 200;

    private final JobRepository jobRepo;
    private final QueueRepository queueRepo;
    private final RetryService retryService;
    private final JobMetrics metrics;
    private final JobEventPublisher eventPublisher;

    /**
     * Cursor for the reconciliation pass. Without it, every pass examines the same oldest
     * 200 jobs; a stranded job behind that window is never seen. The cursor wraps when a
     * page comes back empty, so the scan is continuous.
     */
    private final AtomicInteger reconcileOffset = new AtomicInteger();

    public WorkerMaintenanceServiceImpl(JobRepository jobRepo, QueueRepository queueRepo,
                                        RetryService retryService) {
        this(jobRepo, queueRepo, retryService, JobMetrics.NOOP, null);
    }

    public WorkerMaintenanceServiceImpl(JobRepository jobRepo, QueueRepository queueRepo,
                                        RetryService retryService,
                                        JobMetrics metrics) {
        this(jobRepo, queueRepo, retryService, metrics, null);
    }

    public WorkerMaintenanceServiceImpl(JobRepository jobRepo, QueueRepository queueRepo,
                                        RetryService retryService,
                                        JobMetrics metrics,
                                        JobEventPublisher eventPublisher) {
        this.jobRepo = jobRepo;
        this.queueRepo = queueRepo;
        this.retryService = retryService;
        this.metrics = metrics == null ? JobMetrics.NOOP : metrics;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void promoteRetries() {
        try {
            Instant now = Instant.now();
            List<JobEntity> due = jobRepo.findReadyToRun(JobStatus.RETRY_SCHEDULED, now, 100);

            for (JobEntity job : due) {
                if (!jobRepo.transitionStatusIfCurrent(job.getId(),
                        JobStatus.RETRY_SCHEDULED, JobStatus.QUEUED)) {
                    continue;
                }
                queueRepo.enqueue(job.getId(), job.getPriority(), job.getNextRunAt().toEpochMilli());
                // The job is runnable now, so the scheduler does not have to wait for its
                // next tick to find it.
                if (eventPublisher != null) {
                    eventPublisher.publish(JobEvent.JOB_QUEUED, JobEventData.from(job));
                }
            }
        } catch (Exception e) {
            log.error("Retry promoter failed", e);
        }
    }

    @Override
    public void recoverExpiredLeases() {
        try {
            Instant now = Instant.now();
            List<JobEntity> expired = jobRepo.findReadyToRun(JobStatus.RUNNING, now, 100);

            for (JobEntity job : expired) {
                JobEntity current = jobRepo.findById(job.getId()).orElse(null);
                if (current == null || current.getStatus() != JobStatus.RUNNING) continue;

                String leaseToken = current.getLeaseToken();
                if (leaseToken == null) {
                    log.warn("Job {} is RUNNING without a lease token, skipping recovery", job.getId());
                    continue;
                }

                // The lease check happens inside the write, atomically. Two application
                // instances can both observe this expired lease, but only one compare-and-write
                // can succeed, so the attempt count cannot be incremented twice and a job
                // another worker has already reclaimed cannot be pulled back out from under it.
                String outcome = retryService.handleFailureIfLeaseHeld(
                        current, leaseToken, JobErrorCode.LEASE_LOST,
                        "Worker lease expired", 0L);

                if (outcome == null) {
                    log.info("Job {} was already recovered by another worker, skipping", job.getId());
                    continue;
                }
                metrics.recordLeaseReaped();
                log.warn("Recovered expired lease for job {} -> {}", job.getId(), outcome);
            }
        } catch (Exception e) {
            log.error("Lease reaper failed", e);
        }
    }

    @Override
    public int reconcileQueuedJobs() {
        int restored = 0;
        int offset = reconcileOffset.get();
        try {
            // Ordered by index score, so the oldest queued jobs are considered first.
            // Use the largest Redis-compatible epoch-millisecond value; Instant.MAX
            // cannot be converted to epoch milliseconds and would abort reconciliation.
            List<JobEntity> queued =
                    jobRepo.findReadyToRun(JobStatus.QUEUED,
                        Instant.ofEpochMilli(Long.MAX_VALUE), RECONCILE_BATCH, offset);

            if (queued.isEmpty()) {
                reconcileOffset.set(0);
            } else {
                reconcileOffset.set(offset + RECONCILE_BATCH);
            }

            for (JobEntity job : queued) {
                if (job.getId() == null || job.getPriority() == null) continue;
                if (queueRepo.contains(job.getId(), job.getPriority())) continue;

                long score = job.getNextRunAt() != null
                        ? job.getNextRunAt().toEpochMilli()
                        : System.currentTimeMillis();
                queueRepo.enqueue(job.getId(), job.getPriority(), score);
                restored++;
                // Warn rather than debug: reaching here means a previous attempt lost its
                // queue entry, which is exactly the failure this pass exists to repair.
                log.warn("Re-enqueued queued job {}: its ready-queue entry was missing", job.getId());
            }
        } catch (Exception e) {
            log.error("Queue reconciler failed", e);
        }
        return restored;
    }

    @Override
    public void pruneTerminalIndexes() {
        try {
            long removed = jobRepo.pruneTerminalIndexes();
            if (removed > 0) {
                log.info("Pruned {} expired terminal status-index members", removed);
            }
        } catch (Exception e) {
            log.error("Terminal index sweeper failed", e);
        }
    }
}
