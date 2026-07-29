package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.entity.JobEntity;
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
    private final SimplyDoneProperties config;

    public WorkerMaintenanceServiceImpl(JobRepository jobRepo, QueueRepository queueRepo,
                                         RetryService retryService, SimplyDoneProperties config) {
        this.jobRepo = jobRepo;
        this.queueRepo = queueRepo;
        this.retryService = retryService;
        this.config = config;
    }

    @Scheduled(fixedDelayString = "${simplydone4j.worker.retry-promoter-interval-ms:1000}")
    @Override
    public void promoteRetries() {
        Instant now = Instant.now();
        List<JobEntity> due = jobRepo.findReadyToRun(JobStatus.RETRY_SCHEDULED, now, 100);

        for (JobEntity job : due) {
            job.setStatus(JobStatus.QUEUED);
            job.setUpdatedAt(now);
            jobRepo.save(job);
            queueRepo.enqueue(job.getId(), job.getPriority(), job.getNextRunAt().toEpochMilli());
        }
    }

    @Scheduled(fixedDelayString = "${simplydone4j.worker.lease-reaper-interval-ms:5000}")
    @Override
    public void recoverExpiredLeases() {
        Instant now = Instant.now();
        List<JobEntity> expired = jobRepo.findExpiredLeases(JobStatus.RUNNING, now, 100);

        for (JobEntity job : expired) {
            log.warn("Recovering expired lease for job {}", job.getId());
            retryService.handleFailure(job, "Worker lease expired", 0L);
        }
    }
}
