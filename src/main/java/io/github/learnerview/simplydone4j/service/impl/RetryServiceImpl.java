package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.entity.JobExecutionLog;
import io.github.learnerview.simplydone4j.event.JobEvent;
import io.github.learnerview.simplydone4j.event.JobEventData;
import io.github.learnerview.simplydone4j.event.JobEventPublisher;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobExecutionLogRepository;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.service.RetryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

public final class RetryServiceImpl implements RetryService {
    private static final Logger log = LoggerFactory.getLogger(RetryServiceImpl.class);

    private final JobRepository jobRepo;
    private final JobExecutionLogRepository logRepo;
    private final SimplyDoneProperties config;
    private final JobEventPublisher eventPublisher;

    public RetryServiceImpl(JobRepository jobRepo, JobExecutionLogRepository logRepo,
                             SimplyDoneProperties config, JobEventPublisher eventPublisher) {
        this.jobRepo = jobRepo;
        this.logRepo = logRepo;
        this.config = config;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public String handleFailure(JobEntity job, String errorMessage, long durationMs) {
        int attempt = job.getAttemptCount();
        int maxAttempts = job.getMaxAttempts() > 0 ? job.getMaxAttempts() : config.getRetry().getMaxAttempts();

        logRepo.save(JobExecutionLog.builder()
                .jobId(job.getId())
                .attempt(attempt)
                .status("FAILED")
                .message(errorMessage)
                .durationMs(durationMs)
                .executedAt(Instant.now())
                .build());

        if (attempt + 1 < maxAttempts) {
            long delayMs = (long) (config.getRetry().getInitialDelaySeconds() * 1000L
                    * Math.pow(config.getRetry().getBackoffMultiplier(), attempt));

            Instant nextRun = Instant.now().plusMillis(delayMs);
            job.setStatus(JobStatus.RETRY_SCHEDULED);
            job.setNextRunAt(nextRun);
            job.setVisibleAt(null);
            job.setLeaseOwner(null);
            job.setLeaseToken(null);
            job.setAttemptCount(attempt + 1);
            job.setUpdatedAt(Instant.now());
            jobRepo.save(job);

            log.info("Retrying job {} (attempt {}/{}) in {}ms", job.getId(), attempt + 1, maxAttempts, delayMs);
            eventPublisher.publish(JobEvent.JOB_RETRY, JobEventData.builder()
                    .jobId(job.getId())
                    .jobType(job.getJobType())
                    .producer(job.getProducer())
                    .status("RETRY_SCHEDULED")
                    .attempt(attempt + 1)
                    .maxAttempts(maxAttempts)
                    .timestamp(Instant.now())
                    .build());
            return "RETRY_SCHEDULED";
        } else {
            job.setStatus(JobStatus.DLQ);
            job.setVisibleAt(null);
            job.setLeaseOwner(null);
            job.setLeaseToken(null);
            job.setCompletedAt(Instant.now());
            job.setResult("Max retries exceeded: " + errorMessage);
            job.setUpdatedAt(Instant.now());
            jobRepo.save(job);

            log.warn("Job {} moved to DLQ after {} attempts", job.getId(), maxAttempts);
            eventPublisher.publish(JobEvent.JOB_FAILED, JobEventData.builder()
                    .jobId(job.getId())
                    .jobType(job.getJobType())
                    .producer(job.getProducer())
                    .status("DLQ")
                    .result("Max retries exceeded: " + (errorMessage != null ? errorMessage : ""))
                    .attempt(attempt)
                    .timestamp(Instant.now())
                    .build());
            return "DLQ";
        }
    }

    @Override
    public void logSuccess(JobEntity job, String message, long durationMs) {
        logRepo.save(JobExecutionLog.builder()
                .jobId(job.getId())
                .attempt(job.getAttemptCount())
                .status("SUCCESS")
                .message(message)
                .durationMs(durationMs)
                .executedAt(Instant.now())
                .build());
    }
}
