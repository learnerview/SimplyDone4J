package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.metrics.JobMetrics;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.github.learnerview.simplydone4j.service.RetryService;
import io.github.learnerview.simplydone4j.service.WorkerMaintenanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.List;

public final class WorkerMaintenanceServiceImpl implements WorkerMaintenanceService {
    private static final Logger log = LoggerFactory.getLogger(WorkerMaintenanceServiceImpl.class);

    private final JobRepository jobRepo;
    private final QueueRepository queueRepo;
    private final RetryService retryService;
    private final JobMetrics metrics;
    private final SimplyDoneProperties config;

    public WorkerMaintenanceServiceImpl(JobRepository jobRepo, QueueRepository queueRepo,
                                        RetryService retryService, SimplyDoneProperties config) {
        this(jobRepo, queueRepo, retryService, config, JobMetrics.NOOP);
    }

    public WorkerMaintenanceServiceImpl(JobRepository jobRepo, QueueRepository queueRepo,
                                        RetryService retryService, SimplyDoneProperties config,
                                        JobMetrics metrics) {
        this.jobRepo = jobRepo;
        this.queueRepo = queueRepo;
        this.retryService = retryService;
        this.config = config;
        this.metrics = metrics == null ? JobMetrics.NOOP : metrics;
    }

    @Scheduled(fixedDelayString = "${simplydone4j.worker.retry-promoter-interval-ms:1000}")
    @Override
    public void promoteRetries() {
        try {
            Instant now = Instant.now();
            List<JobEntity> due = jobRepo.findReadyToRun(JobStatus.RETRY_SCHEDULED, now, 100);

            for (JobEntity job : due) {
                job.setStatus(JobStatus.QUEUED);
                job.setUpdatedAt(now);
                jobRepo.save(job);
                queueRepo.enqueue(job.getId(), job.getPriority(), job.getNextRunAt().toEpochMilli());
            }
        } catch (Exception e) {
            log.error("Retry promoter failed", e);
        }
    }

    @Scheduled(fixedDelayString = "${simplydone4j.worker.lease-reaper-interval-ms:5000}")
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
                        current, leaseToken, "Worker lease expired", 0L);

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
}
