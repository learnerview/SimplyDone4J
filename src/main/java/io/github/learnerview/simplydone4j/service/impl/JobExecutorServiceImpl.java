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
import io.github.learnerview.simplydone4j.service.JobSignal;
import io.github.learnerview.simplydone4j.service.RetryService;
import io.github.learnerview.simplydone4j.service.WorkerCapacity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Instant;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class JobExecutorServiceImpl implements JobExecutorService {
    private static final Logger log = LoggerFactory.getLogger(JobExecutorServiceImpl.class);

    private final JobRepository jobRepo;
    private final RetryService retryService;
    private final HandlerRegistry handlerRegistry;
    private final JobEventPublisher eventPublisher;
    private final ThreadPoolTaskExecutor executor;
    private final int defaultTimeoutSeconds;
    private final WorkerCapacity capacity;
    private final JobSignal signal;
    private final LeaseRenewer leaseRenewer;
    private final ScheduledExecutorService timeoutScheduler;

    public JobExecutorServiceImpl(JobRepository jobRepo, RetryService retryService,
                                   HandlerRegistry handlerRegistry, JobEventPublisher eventPublisher,
                                   ThreadPoolTaskExecutor executor, int defaultTimeoutSeconds) {
        this(jobRepo, retryService, handlerRegistry, eventPublisher, executor,
                defaultTimeoutSeconds, null, JobSignal.NOOP, null, null);
    }

    public JobExecutorServiceImpl(JobRepository jobRepo, RetryService retryService,
                                   HandlerRegistry handlerRegistry, JobEventPublisher eventPublisher,
                                   ThreadPoolTaskExecutor executor, int defaultTimeoutSeconds,
                                   WorkerCapacity capacity, JobSignal signal,
                                   LeaseRenewer leaseRenewer,
                                   ScheduledExecutorService timeoutScheduler) {
        this.jobRepo = jobRepo;
        this.retryService = retryService;
        this.handlerRegistry = handlerRegistry;
        this.eventPublisher = eventPublisher;
        this.executor = executor;
        this.defaultTimeoutSeconds = Math.max(1, defaultTimeoutSeconds);
        this.capacity = capacity;
        this.signal = signal == null ? JobSignal.NOOP : signal;
        this.leaseRenewer = leaseRenewer;
        this.timeoutScheduler = timeoutScheduler;
    }

    @Override
    public void execute(JobEntity job) {
        int effectiveTimeout = job.getTimeoutSeconds() != null && job.getTimeoutSeconds() > 0
                ? job.getTimeoutSeconds() : defaultTimeoutSeconds;

        eventPublisher.publish(JobEvent.JOB_STARTED, JobEventData.from(job));

        try {
            executor.submit(() -> executeWithTimeout(job, effectiveTimeout));
        } catch (RuntimeException e) {
            // The scheduler releases the permit when dispatch throws, because this task
            // never entered executeWithTimeout.
            throw e;
        }
    }

    /**
     * Runs the handler on the executor thread instead of submitting a nested future to the
     * same pool. The previous nested future deadlocked when all workers were occupied by
     * outer tasks, each waiting on an inner task that had no thread left to run.
     */
    private void executeWithTimeout(JobEntity job, int timeoutSeconds) {
        long start = System.currentTimeMillis();
        Instant executionStart = Instant.now();
        // Clear a stale interrupt left by a handler that ignored its previous timeout.
        // Pool threads are reused, and an uncleared flag would fail the next job instantly.
        Thread.interrupted();
        String leaseToken = job.getLeaseToken();

        AtomicBoolean timedOut = new AtomicBoolean(false);
        AtomicBoolean completed = new AtomicBoolean(false);
        Thread worker = Thread.currentThread();
        ScheduledFuture<?> watchdog = null;
        String result = null;
        Throwable failure = null;
        try {
            if (leaseRenewer != null) leaseRenewer.start(job.getId(), leaseToken);
            if (timeoutScheduler != null) {
                watchdog = timeoutScheduler.schedule(() -> {
                    if (completed.compareAndSet(false, true)) {
                        timedOut.set(true);
                        worker.interrupt();
                    }
                }, timeoutSeconds, TimeUnit.SECONDS);
            }

            try {
                JobHandler handler = handlerRegistry.getHandler(job.getJobType());
                result = handler.handle(JobContext.from(job, timeoutSeconds, executionStart));
                completed.set(true);
            } catch (InterruptedException e) {
                completed.set(true);
                if (!timedOut.get()) {
                    failure = e;
                    Thread.currentThread().interrupt();
                }
            } catch (Exception e) {
                completed.set(true);
                failure = e;
            }
        } catch (Exception e) {
            failure = e;
        } finally {
            completed.set(true);
            if (watchdog != null) watchdog.cancel(false);
            try {
                if (leaseRenewer != null) leaseRenewer.stop(job.getId(), leaseToken);
            } catch (RuntimeException e) {
                log.warn("Failed to stop lease renewal for job {}", job.getId(), e);
            }
            // Never hand an interrupted thread back to the pool.
            Thread.interrupted();
        }

        long durationMs = System.currentTimeMillis() - start;
        try {
            if (timedOut.get()) {
                handleFailure(job, "Handler timed out after " + timeoutSeconds + "s", durationMs);
            } else if (failure != null) {
                handleFailure(job, failure.getMessage() != null ? failure.getMessage() : "Unknown error",
                        durationMs);
            } else {
                handleSuccess(job, result, durationMs);
            }
        } catch (RuntimeException e) {
            // The handler has finished, but its terminal write failed. Keep the permit
            // occupied until this point is reached so the scheduler cannot replace a job
            // whose outcome has not been recorded yet. The lease reaper will recover the
            // job once the lease expires; at-least-once execution still applies.
            log.error("Job {} finished but its outcome could not be recorded; "
                    + "the lease reaper will recover it", job.getId(), e);
        } finally {
            // Release only after the outcome write and its side channels have completed.
            // Releasing earlier lets a CallerRunsPolicy task grow the worker thread and let
            // the scheduler thread claim more work while the previous terminal write is
            // still in flight.
            releaseCapacity();
            signal.signal();
        }
    }

    private void handleSuccess(JobEntity job, String result, long durationMs) {
        String leaseToken = job.getLeaseToken();
        job.setStatus(JobStatus.SUCCESS);
        job.setResult(result);
        job.setNextRunAt(null);
        job.setVisibleAt(null);
        job.setLeaseOwner(null);
        job.setLeaseToken(null);
        job.setCompletedAt(Instant.now());
        job.setUpdatedAt(Instant.now());

        boolean persisted = leaseToken != null
                ? jobRepo.saveIfLeaseHeld(job, leaseToken)
                : saveUnfenced(job);
        if (!persisted) {
            log.warn("Job {} success discarded - the lease was lost before the write", job.getId());
            return;
        }

        retryService.logSuccess(job, "Handler executed successfully", durationMs);
        eventPublisher.publish(JobEvent.JOB_COMPLETED, JobEventData.builder()
                .jobId(job.getId())
                .jobType(job.getJobType())
                .producer(job.getProducer())
                .status(JobStatus.SUCCESS.name())
                .durationMs(durationMs)
                .timestamp(Instant.now())
                .build());

    }

    private void handleFailure(JobEntity job, String errorMessage, long durationMs) {
        String leaseToken = job.getLeaseToken();
        String status = leaseToken != null
                ? retryService.handleFailureIfLeaseHeld(job, leaseToken, errorMessage, durationMs)
                : retryService.handleFailure(job, errorMessage, durationMs);
        if (status == null) {
            log.warn("Job {} failure discarded - the lease was lost before the write", job.getId());
            return;
        }
    }

    private boolean saveUnfenced(JobEntity job) {
        jobRepo.save(job);
        return true;
    }

    private void releaseCapacity() {
        if (capacity != null) capacity.release();
    }
}
