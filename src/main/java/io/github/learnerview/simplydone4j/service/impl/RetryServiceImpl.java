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
import io.github.learnerview.simplydone4j.service.RetryPolicy;
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
    private final RetryPolicy retryPolicy;

    public RetryServiceImpl(JobRepository jobRepo, JobExecutionLogRepository logRepo,
                             SimplyDoneProperties config, JobEventPublisher eventPublisher,
                             RetryPolicy retryPolicy) {
        this.jobRepo = jobRepo;
        this.logRepo = logRepo;
        this.config = config;
        this.eventPublisher = eventPublisher;
        this.retryPolicy = retryPolicy;
    }

    @Override
    public String handleFailure(JobEntity job, String errorMessage, long durationMs) {
        return applyFailure(job, null, errorMessage, durationMs);
    }

    @Override
    public String handleFailureIfLeaseHeld(JobEntity job, String expectedLeaseToken,
                                          String errorMessage, long durationMs) {
        return applyFailure(job, expectedLeaseToken, errorMessage, durationMs);
    }

    /**
     * @param expectedLeaseToken when non-null, the state transition is applied only if the
     *                           persisted lease still matches, atomically.
     */
    private String applyFailure(JobEntity job, String expectedLeaseToken,
                                String errorMessage, long durationMs) {
        int attempt = job.getAttemptCount();
        int maxAttempts = job.getMaxAttempts() > 0 ? job.getMaxAttempts() : config.getRetry().getMaxAttempts();
        boolean willRetry = attempt + 1 < maxAttempts;
        long delayMs = willRetry ? retryPolicy.calculateDelayMs(attempt) : 0L;

        if (willRetry) {
            job.setStatus(JobStatus.RETRY_SCHEDULED);
            job.setNextRunAt(Instant.now().plusMillis(delayMs));
            job.setVisibleAt(null);
            job.setLeaseOwner(null);
            job.setLeaseToken(null);
            job.setAttemptCount(attempt + 1);
            job.setUpdatedAt(Instant.now());
        } else {
            job.setStatus(JobStatus.DLQ);
            job.setVisibleAt(null);
            job.setLeaseOwner(null);
            job.setLeaseToken(null);
            job.setNextRunAt(null);
            job.setCompletedAt(Instant.now());
            job.setResult("Max retries exceeded: " + errorMessage);
            job.setUpdatedAt(Instant.now());
        }

        boolean persisted = expectedLeaseToken == null
                ? saveUnfenced(job)
                : jobRepo.saveIfLeaseHeld(job, expectedLeaseToken);

        if (!persisted) {
            log.warn("Job {} failure discarded - the lease was lost before the write", job.getId());
            return null;
        }

        // Recorded only after the state transition succeeded, so the log never claims an
        // attempt that a competing worker had already superseded.
        logRepo.save(JobExecutionLog.builder()
                .jobId(job.getId())
                .attempt(attempt)
                .status("FAILED")
                .message(errorMessage)
                .durationMs(durationMs)
                .executedAt(Instant.now())
                .build());

        if (willRetry) {
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
        }

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

    private boolean saveUnfenced(JobEntity job) {
        jobRepo.save(job);
        return true;
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
