package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.event.JobEvent;
import io.github.learnerview.simplydone4j.event.JobEventData;
import io.github.learnerview.simplydone4j.event.JobEventPublisher;
import io.github.learnerview.simplydone4j.exception.InvalidPayloadException;
import io.github.learnerview.simplydone4j.exception.UnknownJobTypeException;
import io.github.learnerview.simplydone4j.handler.HandlerRegistry;
import io.github.learnerview.simplydone4j.handler.JobContext;
import io.github.learnerview.simplydone4j.handler.JobHandler;
import io.github.learnerview.simplydone4j.metrics.JobMetrics;
import io.github.learnerview.simplydone4j.model.JobErrorCode;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.github.learnerview.simplydone4j.service.JobExecutorOptions;
import io.github.learnerview.simplydone4j.service.JobExecutorService;
import io.github.learnerview.simplydone4j.service.RetryService;
import io.github.learnerview.simplydone4j.service.UniquenessGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs claimed jobs on the worker pool.
 *
 * <p>This type is the orchestration: it picks a handler, applies the uniqueness guard,
 * runs the handler under a timeout watchdog and a cancellation watcher, and hands the
 * outcome to {@link JobOutcomeRecorder}. Lease renewal and progress writes are separate
 * collaborators so each rule has one home.</p>
 */
public final class JobExecutorServiceImpl implements JobExecutorService {
    private static final Logger log = LoggerFactory.getLogger(JobExecutorServiceImpl.class);

    /**
     * How often a running job is asked whether it has been cancelled.
     *
     * <p>One second is fast enough that a cancel feels immediate and slow enough that a
     * fleet of running jobs does not turn the store into a hot loop.</p>
     */
    private static final long CANCEL_POLL_INTERVAL_SECONDS = 1L;

    private final JobRepository jobRepo;
    private final QueueRepository queueRepo;
    private final HandlerRegistry handlerRegistry;
    private final JobEventPublisher eventPublisher;
    private final UniquenessGuard uniquenessGuard;
    private final JobOutcomeRecorder outcomes;
    private final LeaseRenewer leaseRenewer;
    private final int uniquenessLockTtlSeconds;
    private final ThreadPoolTaskExecutor executor;
    private final ScheduledExecutorService timeoutScheduler;
    private final JobMetrics metrics;
    private final int defaultTimeoutSeconds;

    public JobExecutorServiceImpl(JobRepository jobRepo, QueueRepository queueRepo,
                                   RetryService retryService,
                                   HandlerRegistry handlerRegistry, JobEventPublisher eventPublisher,
                                   ThreadPoolTaskExecutor executor,
                                   JobExecutorOptions options) {
        this.jobRepo = jobRepo;
        this.queueRepo = queueRepo;
        this.handlerRegistry = handlerRegistry;
        this.eventPublisher = eventPublisher;
        this.uniquenessGuard = options.getUniquenessGuard() == null
                ? UniquenessGuard.DISABLED : options.getUniquenessGuard();
        this.uniquenessLockTtlSeconds = options.getUniquenessLockTtlSeconds();
        this.executor = executor;
        this.timeoutScheduler = options.getTimer();
        this.defaultTimeoutSeconds = options.getDefaultTimeoutSeconds();
        this.metrics = options.getMetrics() == null ? JobMetrics.NOOP : options.getMetrics();
        this.outcomes = new JobOutcomeRecorder(jobRepo, queueRepo, retryService, eventPublisher,
                uniquenessGuard, metrics, options.getOverlapDeferSeconds());
        this.leaseRenewer = new LeaseRenewer(jobRepo, timeoutScheduler, options.getLeaseTimeoutSeconds());
    }

    @Override
    public void execute(JobEntity job) {
        int effectiveTimeout = job.getTimeoutSeconds() != null && job.getTimeoutSeconds() > 0
                ? job.getTimeoutSeconds() : defaultTimeoutSeconds;

        try {
            executor.submit(() -> {
                eventPublisher.publish(JobEvent.JOB_STARTED, JobEventData.from(job));
                executeWithTimeout(job, effectiveTimeout);
            });
        } catch (RejectedExecutionException e) {
            requeueRejected(job, e);
        }
    }

    /**
     * A saturated pool rejects the submit after the scheduler has already moved the job
     * to {@code RUNNING}. Returning it to {@code QUEUED} and re-arming its queue entry is
     * what makes rejection mean "not now" rather than "lost until the lease expires". The
     * attempt budget is untouched: no handler ran, so no attempt was spent.
     *
     * <p>If the transition or enqueue fails, the job is left for the lease reaper or the
     * queue reconciler rather than being forced into a state with no recovery path.</p>
     */
    private void requeueRejected(JobEntity job, RuntimeException rejection) {
        JobPriority priority = job.getPriority() != null ? job.getPriority() : JobPriority.NORMAL;
        log.warn("Worker pool rejected job {}; returning it to the {} queue without consuming "
                + "an attempt ({})", job.getId(), priority, rejection.getMessage());

        boolean moved;
        try {
            moved = jobRepo.transitionStatusIfCurrent(job.getId(), JobStatus.RUNNING, JobStatus.QUEUED);
        } catch (RuntimeException e) {
            log.error("Could not return rejected job {} to QUEUED; leaving it RUNNING for the "
                    + "lease reaper", job.getId(), e);
            return;
        }
        if (!moved) {
            // A cancel or terminal writer won the race; there is nothing left to run.
            return;
        }

        try {
            queueRepo.enqueue(job.getId(), priority, System.currentTimeMillis());
        } catch (RuntimeException e) {
            log.error("Job {} is QUEUED but could not be re-enqueued after executor rejection; "
                    + "the reconciler will restore it", job.getId(), e);
        }
    }

    /**
     * Runs the handler on the calling worker thread and enforces the timeout with a
     * watchdog on a separate scheduler.
     *
     * <p>The handler runs inline rather than being submitted back to the same pool and
     * awaited: with a bounded pool that self-deadlocks, because every worker ends up
     * waiting on a task queued behind those very threads.</p>
     *
     * <p>Exactly one of the two paths may record an outcome, because
     * {@link ScheduledFuture#cancel} returns {@code false} once the watchdog has begun:
     * if the handler returns in time the worker writes the success, and if it does not the
     * watchdog writes the failure and the late success is dropped. The fencing check makes
     * that race safe at the store even if both paths were somehow to run.</p>
     */
    private void executeWithTimeout(JobEntity job, int timeoutSeconds) {
        long start = System.currentTimeMillis();
        ScheduledFuture<?> leaseRenewal = leaseRenewer.start(job);

        try {
            JobHandler handler;
            try {
                handler = handlerRegistry.getHandler(job.getJobType());
            } catch (UnknownJobTypeException e) {
                // No handler will ever appear for this job type, so every attempt would
                // fail identically. Fail the job terminally instead of spending the budget.
                outcomes.terminalFailure(job, JobErrorCode.UNKNOWN_JOB_TYPE, e.getMessage(),
                        System.currentTimeMillis() - start);
                return;
            } catch (RuntimeException e) {
                outcomes.failure(job, JobErrorCode.HANDLER_THREW,
                        e.getMessage() != null ? e.getMessage() : "Unknown error",
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

            // The owner is the job id, so the lock is per job instance rather than per
            // worker thread. A worker that dies mid-handler still releases nothing, and the
            // TTL eventually frees the key, but no live job can evict another's lock.
            String owner = job.getId();
            long lockTtlSeconds = uniquenessLockTtlSeconds > 0
                    ? uniquenessLockTtlSeconds
                    : Math.max(1, timeoutSeconds + 30L);
            Duration lockTtl = Duration.ofSeconds(lockTtlSeconds);
            boolean acquired;
            try {
                acquired = uniquenessGuard.tryAcquire(uniqueKey, owner, lockTtl);
            } catch (RuntimeException e) {
                // A Redis blip must not wedge the pipeline. Fail open and let the job run:
                // the uniqueness guarantee degrades, but work still gets done.
                log.warn("Uniqueness guard unavailable for job {}; running without the guarantee",
                        job.getId(), e);
                acquired = true;
            }

            if (!acquired) {
                metrics.recordOverlapped();
                outcomes.deferForOverlap(job, System.currentTimeMillis() - start);
                return;
            }

            try {
                runHandler(job, handler, timeoutSeconds, start);
            } finally {
                outcomes.releaseQuietly(job, owner);
            }
        } finally {
            if (leaseRenewal != null) {
                leaseRenewal.cancel(false);
            }
        }
    }

    /**
     * Runs the handler under a watchdog, recording exactly one terminal outcome.
     *
     * <p>Building the context is inside this method's guard because it can itself throw on
     * a malformed job. An exception escaping here would unwind into the worker pool, which
     * swallows it, and the job would sit in RUNNING until the reaper picked it up: no
     * outcome, no log trace.</p>
     */
    private void runHandler(JobEntity job, JobHandler handler, int timeoutSeconds, long start) {
        JobContext context;
        try {
            context = JobContext.from(job, new ProgressReporter(job.getId(), jobRepo));
        } catch (RuntimeException e) {
            outcomes.failure(job, JobErrorCode.HANDLER_THREW,
                    "Could not build job context: " + e.getMessage(),
                    System.currentTimeMillis() - start, false);
            return;
        }

        // A cancellation that arrived between the claim and the start of the handler is
        // caught before the work begins, not after it.
        if (isCancellationRequested(job, context)) {
            outcomes.cancellation(job, System.currentTimeMillis() - start);
            return;
        }

        ScheduledFuture<?> watchdog = timeoutScheduler.schedule(
                () -> outcomes.failure(job, JobErrorCode.TIMEOUT,
                        "Handler timed out after " + timeoutSeconds + "s",
                        System.currentTimeMillis() - start, true),
                timeoutSeconds, TimeUnit.SECONDS);

        // The watcher is what makes cancellation real for a running job: it polls the
        // store and raises the flag the handler sees through the context.
        ScheduledFuture<?> cancelWatcher = timeoutScheduler.scheduleWithFixedDelay(
                () -> isCancellationRequested(job, context),
                CANCEL_POLL_INTERVAL_SECONDS, CANCEL_POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);

        try {
            String result = handler.handle(context);
            if (watchdog.cancel(false)) {
                if (context.isCancellationRequested()) {
                    outcomes.cancellation(job, System.currentTimeMillis() - start);
                } else {
                    outcomes.success(job, result, System.currentTimeMillis() - start);
                }
            } else {
                log.warn("Job {} finished after its {}s timeout; the watchdog already recorded the failure",
                        job.getId(), timeoutSeconds);
            }
        } catch (InvalidPayloadException e) {
            // The payload is the same on every attempt, so a payload the handler cannot
            // read will not read any better next time. Fail the job terminally.
            if (watchdog.cancel(false)) {
                outcomes.terminalFailure(job, JobErrorCode.PAYLOAD_INVALID, e.getMessage(),
                        System.currentTimeMillis() - start);
            }
        } catch (Exception e) {
            if (watchdog.cancel(false)) {
                if (context.isCancellationRequested()) {
                    // The handler stopped because it was cancelled, not because it failed.
                    outcomes.cancellation(job, System.currentTimeMillis() - start);
                } else {
                    outcomes.failure(job, JobErrorCode.HANDLER_THREW,
                            e.getMessage() != null ? e.getMessage() : "Unknown error",
                            System.currentTimeMillis() - start, false);
                }
            }
        } finally {
            cancelWatcher.cancel(false);
        }
    }

    /**
     * Polls the store for a cancellation request and raises it on the context.
     *
     * <p>The flag is read once a second rather than on every handler step: a cancel is
     * rare, and a hot loop in the store would cost more than the delay it saves. The
     * handler sees the cancellation through {@link JobContext#isCancellationRequested()},
     * which it has to check itself -- the engine cannot interrupt a handler that never
     * asks.</p>
     *
     * @return whether a cancellation is pending
     */
    private boolean isCancellationRequested(JobEntity job, JobContext context) {
        try {
            if (jobRepo.isCancellationRequested(job.getId())) {
                context.requestCancellation();
                return true;
            }
        } catch (RuntimeException e) {
            // A Redis blip must not fail the job. The next poll tries again, and a
            // cancellation that is never delivered leaves the job running, which is
            // better than failing a job that is doing its work.
            log.warn("Could not read the cancellation flag for job {}", job.getId(), e);
        }
        return context.isCancellationRequested();
    }
}
