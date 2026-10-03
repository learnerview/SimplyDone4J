package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.event.JobEvent;
import io.github.learnerview.simplydone4j.event.JobEventData;
import io.github.learnerview.simplydone4j.event.JobEventPublisher;
import io.github.learnerview.simplydone4j.handler.HandlerRegistry;
import io.github.learnerview.simplydone4j.handler.JobContext;
import io.github.learnerview.simplydone4j.handler.JobHandler;
import io.github.learnerview.simplydone4j.metrics.JobMetrics;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.github.learnerview.simplydone4j.service.JobExecutorService;
import io.github.learnerview.simplydone4j.service.RetryService;
import io.github.learnerview.simplydone4j.service.UniquenessGuard;
import io.github.learnerview.simplydone4j.service.WebhookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class JobExecutorServiceImpl implements JobExecutorService {
    private static final Logger log = LoggerFactory.getLogger(JobExecutorServiceImpl.class);

    private final JobRepository jobRepo;
    private final QueueRepository queueRepo;
    private final RetryService retryService;
    private final HandlerRegistry handlerRegistry;
    private final JobEventPublisher eventPublisher;
    private final WebhookService webhookService;
    private final UniquenessGuard uniquenessGuard;
    private final int overlapDeferSeconds;
    private final int uniquenessLockTtlSeconds;
    private final ThreadPoolTaskExecutor executor;
    private final ScheduledExecutorService timeoutScheduler;
    private final JobMetrics metrics;
    private final int defaultTimeoutSeconds;

    public JobExecutorServiceImpl(JobRepository jobRepo, QueueRepository queueRepo,
                                   RetryService retryService,
                                   HandlerRegistry handlerRegistry, JobEventPublisher eventPublisher,
                                   WebhookService webhookService,
                                   ThreadPoolTaskExecutor executor,
                                   ScheduledExecutorService timeoutScheduler,
                                   int defaultTimeoutSeconds) {
        this(jobRepo, queueRepo, retryService, handlerRegistry, eventPublisher, webhookService,
                UniquenessGuard.DISABLED, 0, 0, executor, timeoutScheduler, defaultTimeoutSeconds,
                JobMetrics.NOOP);
    }

    public JobExecutorServiceImpl(JobRepository jobRepo, QueueRepository queueRepo,
                                   RetryService retryService,
                                   HandlerRegistry handlerRegistry, JobEventPublisher eventPublisher,
                                   WebhookService webhookService,
                                   UniquenessGuard uniquenessGuard,
                                   int overlapDeferSeconds,
                                   ThreadPoolTaskExecutor executor,
                                   ScheduledExecutorService timeoutScheduler,
                                   int defaultTimeoutSeconds,
                                   JobMetrics metrics) {
        this(jobRepo, queueRepo, retryService, handlerRegistry, eventPublisher, webhookService,
                uniquenessGuard, overlapDeferSeconds, 0, executor, timeoutScheduler, defaultTimeoutSeconds,
                metrics);
    }

    public JobExecutorServiceImpl(JobRepository jobRepo, QueueRepository queueRepo,
                                   RetryService retryService,
                                   HandlerRegistry handlerRegistry, JobEventPublisher eventPublisher,
                                   WebhookService webhookService,
                                   UniquenessGuard uniquenessGuard,
                                   int overlapDeferSeconds,
                                   int uniquenessLockTtlSeconds,
                                   ThreadPoolTaskExecutor executor,
                                   ScheduledExecutorService timeoutScheduler,
                                   int defaultTimeoutSeconds,
                                   JobMetrics metrics) {
        this.jobRepo = jobRepo;
        this.queueRepo = queueRepo;
        this.retryService = retryService;
        this.handlerRegistry = handlerRegistry;
        this.eventPublisher = eventPublisher;
        this.webhookService = webhookService;
        this.uniquenessGuard = uniquenessGuard == null ? UniquenessGuard.DISABLED : uniquenessGuard;
        this.overlapDeferSeconds = overlapDeferSeconds;
        this.uniquenessLockTtlSeconds = uniquenessLockTtlSeconds;
        this.executor = executor;
        this.timeoutScheduler = timeoutScheduler;
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
        this.metrics = metrics == null ? JobMetrics.NOOP : metrics;
    }

    @Override
    public void execute(JobEntity job) {
        int effectiveTimeout = job.getTimeoutSeconds() != null && job.getTimeoutSeconds() > 0
                ? job.getTimeoutSeconds() : defaultTimeoutSeconds;

        eventPublisher.publish(JobEvent.JOB_STARTED, JobEventData.from(job));

        executor.submit(() -> executeWithTimeout(job, effectiveTimeout));
    }

    /**
     * Runs the handler on the calling worker thread and enforces the timeout with a
     * watchdog on a separate scheduler.
     *
     * <p>The previous shape submitted the handler back to {@code executor} and blocked on
     * {@code future.get(timeout)} from a thread of that same pool. With a bounded pool that
     * self-deadlocks: every worker thread ends up waiting on a handler task that is queued
     * behind those very threads, so nothing starts and the whole batch times out at once.
     * Running the handler inline removes the nesting; the watchdog supplies the deadline.</p>
     *
     * <p>Exactly one of the two paths may record an outcome, because {@link ScheduledFuture#cancel}
     * returns {@code false} once the watchdog has begun: if the handler returns in time the
     * worker writes the success, and if it does not the watchdog writes the failure and the
     * late success is dropped. The fencing check makes that race safe at the store even if
     * both paths were somehow to run.</p>
     */
    private void executeWithTimeout(JobEntity job, int timeoutSeconds) {
        long start = System.currentTimeMillis();

        JobHandler handler;
        try {
            handler = handlerRegistry.getHandler(job.getJobType());
        } catch (RuntimeException e) {
            handleFailureWithFencing(job, e.getMessage() != null ? e.getMessage() : "Unknown error",
                    System.currentTimeMillis() - start, false);
            return;
        }

        // Unkeyed jobs never touch Redis, so the default-off configuration costs
        // nothing on the hot path.
        String uniqueKey = job.getUniqueKey();
        if (uniqueKey == null || uniqueKey.isBlank()) {
            runHandler(job, handler, timeoutSeconds, start);
            return;
        }

        // The owner is the job id, so the lock is per job instance rather than per worker
        // thread. A worker that dies mid-handler still releases nothing, and the TTL
        // eventually frees the key, but no live job can ever evict another's lock.
        String owner = job.getId();
        // Default the TTL to the job timeout plus a grace margin so a slow run keeps its
        // lock until it really finishes. An explicit uniqueness.ttl-seconds overrides it.
        long lockTtlSeconds = uniquenessLockTtlSeconds > 0
                ? uniquenessLockTtlSeconds
                : Math.max(1, timeoutSeconds + 30L);
        Duration lockTtl = Duration.ofSeconds(lockTtlSeconds);
        boolean acquired;
        try {
            acquired = uniquenessGuard.tryAcquire(uniqueKey, owner, lockTtl);
        } catch (RuntimeException e) {
            // A Redis blip must not wedge the pipeline. Fail open and let the job run:
            // the uniqueness guarantee degrades, but work still gets done, which is the
            // trade-off an operator would pick over a stalled queue.
            log.warn("Uniqueness guard unavailable for job {}; running without the guarantee", job.getId(), e);
            acquired = true;
        }

        if (!acquired) {
            metrics.recordOverlapped();
            deferForOverlap(job, System.currentTimeMillis() - start);
            return;
        }

        try {
            runHandler(job, handler, timeoutSeconds, start);
        } finally {
            releaseQuietly(job, owner);
        }
    }

    /**
     * Runs the handler under a watchdog, recording exactly one terminal outcome.
     *
     * <p>Building the context is inside this method's guard rather than the caller's
     * because it can itself throw on a malformed job. An exception escaping here would
     * unwind into the worker pool, which swallows it, and the job would sit in RUNNING
     * until the reaper picked it up: no outcome, no webhook, no log trace. Failing it
     * explicitly keeps the retry path responsible for every attempt.</p>
     */
    private void runHandler(JobEntity job, JobHandler handler, int timeoutSeconds, long start) {
        JobContext context;
        try {
            context = JobContext.from(job);
        } catch (RuntimeException e) {
            handleFailureWithFencing(job,
                    "Could not build job context: " + e.getMessage(),
                    System.currentTimeMillis() - start, false);
            return;
        }

        ScheduledFuture<?> watchdog = timeoutScheduler.schedule(
                () -> handleFailureWithFencing(job,
                        "Handler timed out after " + timeoutSeconds + "s",
                        System.currentTimeMillis() - start, true),
                timeoutSeconds, TimeUnit.SECONDS);

        try {
            String result = handler.handle(context);
            if (watchdog.cancel(false)) {
                handleSuccess(job, result, System.currentTimeMillis() - start);
            } else {
                log.warn("Job {} finished after its {}s timeout; the watchdog already recorded the failure",
                        job.getId(), timeoutSeconds);
            }
        } catch (Exception e) {
            if (watchdog.cancel(false)) {
                handleFailureWithFencing(job,
                        e.getMessage() != null ? e.getMessage() : "Unknown error",
                        System.currentTimeMillis() - start, false);
            }
        }
    }

    /**
     * Puts a job back on the queue because its {@code uniqueKey} is busy.
     *
     * <p>Deliberately not routed through the retry path: nothing failed, so consuming an
     * attempt would burn the budget on a job that never had a chance to run. A job
     * overlapped many times in a row would otherwise land in the DLQ for being
     * "unlucky" rather than for being broken.</p>
     *
     * <p>The write is fenced like every other transition, so a job that was reaped or
     * cancelled while it sat in the queue is left alone instead of being resurrected.</p>
     */
    private void deferForOverlap(JobEntity job, long durationMs) {
        String issuedLeaseToken = job.getLeaseToken();

        JobEntity current = jobRepo.findById(job.getId()).orElse(null);
        if (current == null) return;

        Instant nextRunAt = Instant.now().plusSeconds(overlapDeferSeconds);
        current.setStatus(JobStatus.QUEUED);
        current.setNextRunAt(nextRunAt);
        current.setVisibleAt(null);
        current.setLeaseOwner(null);
        current.setLeaseToken(null);
        current.setUpdatedAt(Instant.now());

        if (!jobRepo.saveIfLeaseHeld(current, issuedLeaseToken)) {
            metrics.recordFencedWriteRejected();
            metrics.recordCompleted(JobMetrics.Outcome.DISCARDED, durationMs);
            log.warn("Job {} overlap deferral discarded - lease was lost before the write", job.getId());
            return;
        }

        queueRepo.enqueue(current.getId(),
                current.getPriority() != null ? current.getPriority() : JobPriority.NORMAL,
                nextRunAt.toEpochMilli());
        metrics.recordCompleted(JobMetrics.Outcome.DEFERRED, durationMs);
        log.debug("Job {} deferred {}s because its unique key is in use",
                job.getId(), overlapDeferSeconds);
    }

    private void releaseQuietly(JobEntity job, String owner) {
        try {
            uniquenessGuard.release(job.getUniqueKey(), owner);
        } catch (RuntimeException e) {
            // The TTL is the backstop. Losing the release only delays the key's reuse.
            log.warn("Failed to release unique key for job {}; relying on TTL", job.getId(), e);
        }
    }

    private void handleSuccess(JobEntity job, String result, long durationMs) {
        // Capture the token we were issued *before* touching the entity. Java evaluates
        // arguments left to right, so reading job.getLeaseToken() inline would observe
        // the mutation below whenever findById hands back the same instance.
        String issuedLeaseToken = job.getLeaseToken();

        JobEntity current = jobRepo.findById(job.getId()).orElse(null);
        if (current == null) return;

        current.setStatus(JobStatus.SUCCESS);
        current.setResult(result);
        current.setNextRunAt(null);
        current.setVisibleAt(null);
        current.setLeaseOwner(null);
        current.setLeaseToken(null);
        current.setCompletedAt(Instant.now());
        current.setUpdatedAt(Instant.now());

        if (!jobRepo.saveIfLeaseHeld(current, issuedLeaseToken)) {
            metrics.recordFencedWriteRejected();
            metrics.recordCompleted(JobMetrics.Outcome.DISCARDED, durationMs);
            log.warn("Job {} success discarded - lease was lost before the write", job.getId());
            return;
        }

        metrics.recordCompleted(JobMetrics.Outcome.SUCCESS, durationMs);
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

    private void handleFailureWithFencing(JobEntity job, String errorMessage, long durationMs, boolean timedOut) {
        String issuedLeaseToken = job.getLeaseToken();

        JobEntity current = jobRepo.findById(job.getId()).orElse(null);
        if (current == null) return;

        String status = retryService.handleFailureIfLeaseHeld(current, issuedLeaseToken,
                errorMessage, durationMs);
        if (status == null) {
            metrics.recordFencedWriteRejected();
            metrics.recordCompleted(JobMetrics.Outcome.DISCARDED, durationMs);
            log.warn("Job {} failure discarded - lease was lost before the write", job.getId());
            return;
        }

        if (timedOut) {
            metrics.recordCompleted(JobMetrics.Outcome.TIMEOUT, durationMs);
        } else {
            metrics.recordCompleted(
                    JobStatus.DLQ.name().equals(status) ? JobMetrics.Outcome.DEAD_LETTER : JobMetrics.Outcome.FAILED,
                    durationMs);
        }
        webhookService.fireCallback(current, status, errorMessage);
    }
}
