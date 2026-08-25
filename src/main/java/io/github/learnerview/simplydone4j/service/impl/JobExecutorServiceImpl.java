package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.event.JobEvent;
import io.github.learnerview.simplydone4j.event.JobEventData;
import io.github.learnerview.simplydone4j.event.JobEventPublisher;
import io.github.learnerview.simplydone4j.handler.HandlerRegistry;
import io.github.learnerview.simplydone4j.handler.JobContext;
import io.github.learnerview.simplydone4j.handler.JobHandler;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.service.JobExecutorService;
import io.github.learnerview.simplydone4j.service.RetryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import io.github.learnerview.simplydone4j.service.WebhookService;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class JobExecutorServiceImpl implements JobExecutorService {
    private static final Logger log = LoggerFactory.getLogger(JobExecutorServiceImpl.class);

    private final JobRepository jobRepo;
    private final RetryService retryService;
    private final HandlerRegistry handlerRegistry;
    private final JobEventPublisher eventPublisher;
    private final WebhookService webhookService;
    private final ThreadPoolTaskExecutor executor;
    private final int defaultTimeoutSeconds;

    public JobExecutorServiceImpl(JobRepository jobRepo, RetryService retryService,
                                   HandlerRegistry handlerRegistry, JobEventPublisher eventPublisher,
                                   WebhookService webhookService,
                                   ThreadPoolTaskExecutor executor, int defaultTimeoutSeconds) {
        this.jobRepo = jobRepo;
        this.retryService = retryService;
        this.handlerRegistry = handlerRegistry;
        this.eventPublisher = eventPublisher;
        this.webhookService = webhookService;
        this.executor = executor;
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
    }

    @Override
    public void execute(JobEntity job) {
        int effectiveTimeout = job.getTimeoutSeconds() != null && job.getTimeoutSeconds() > 0
                ? job.getTimeoutSeconds() : defaultTimeoutSeconds;

        eventPublisher.publish(JobEvent.JOB_STARTED, JobEventData.from(job));

        executor.submit(() -> executeWithTimeout(job, effectiveTimeout));
    }

    private void executeWithTimeout(JobEntity job, int timeoutSeconds) {
        long start = System.currentTimeMillis();
        try {
            JobHandler handler = handlerRegistry.getHandler(job.getJobType());
            JobContext context = JobContext.from(job);

            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return handler.handle(context);
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }, executor);

            String result = future.get(timeoutSeconds, TimeUnit.SECONDS);
            handleSuccess(job, result, System.currentTimeMillis() - start);
        } catch (TimeoutException e) {
            handleFailureWithFencing(job, "Handler timed out after " + timeoutSeconds + "s", System.currentTimeMillis() - start);
        } catch (Exception e) {
            handleFailureWithFencing(job,
                    e.getMessage() != null ? e.getMessage() : "Unknown error", System.currentTimeMillis() - start);
        }
    }

    private void handleSuccess(JobEntity originalJob, String result, long durationMs) {
        JobEntity current = jobRepo.findById(originalJob.getId()).orElse(null);
        if (current == null) return;
        if (!fencingTokenMatches(originalJob, current)) {
            log.warn("Job {} lease token mismatch — another worker may own it", originalJob.getId());
            return;
        }

        current.setStatus(JobStatus.SUCCESS);
        current.setResult(result);
        current.setVisibleAt(null);
        current.setLeaseOwner(null);
        current.setLeaseToken(null);
        current.setCompletedAt(Instant.now());
        current.setUpdatedAt(Instant.now());
        jobRepo.save(current);

        retryService.logSuccess(current, "Handler executed successfully", durationMs);
        eventPublisher.publish(JobEvent.JOB_COMPLETED, JobEventData.builder()
                .jobId(current.getId())
                .jobType(current.getJobType())
                .producer(current.getProducer())
                .status(JobStatus.SUCCESS.name())
                .durationMs(durationMs)
                .timestamp(Instant.now())
                .build());

        webhookService.fireCallback(current, "SUCCESS", null);
    }

    private void handleFailureWithFencing(JobEntity originalJob, String errorMessage, long durationMs) {
        JobEntity current = jobRepo.findById(originalJob.getId()).orElse(null);
        if (current == null) return;
        if (!fencingTokenMatches(originalJob, current)) {
            log.warn("Job {} lease token mismatch on failure — skipped", originalJob.getId());
            return;
        }
        String status = retryService.handleFailure(current, errorMessage, durationMs);
        webhookService.fireCallback(current, status != null ? status : "FAILED", errorMessage);
    }

    private static boolean fencingTokenMatches(JobEntity original, JobEntity current) {
        return original.getLeaseToken() != null
            && original.getLeaseToken().equals(current.getLeaseToken());
    }
}
