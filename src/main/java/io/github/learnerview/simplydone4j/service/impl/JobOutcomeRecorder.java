package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.event.JobEvent;
import io.github.learnerview.simplydone4j.event.JobEventData;
import io.github.learnerview.simplydone4j.event.JobEventPublisher;
import io.github.learnerview.simplydone4j.metrics.JobMetrics;
import io.github.learnerview.simplydone4j.model.JobErrorCode;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.github.learnerview.simplydone4j.service.RetryService;
import io.github.learnerview.simplydone4j.service.UniquenessGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * Records the outcome of one attempt.
 *
 * <p>Every terminal write is fenced with the lease token the attempt was issued. A
 * worker whose lease was reaped must not be able to overwrite the state of the attempt
 * that replaced it, so the write either happens under the token or is discarded. Keeping
 * this in one type makes the fencing rule, the metrics and the event fan-out
 * uniform across success, failure, cancellation and deferral.</p>
 */
final class JobOutcomeRecorder {

    private static final Logger log = LoggerFactory.getLogger(JobOutcomeRecorder.class);

    private final JobRepository jobRepo;
    private final QueueRepository queueRepo;
    private final RetryService retryService;
    private final JobEventPublisher eventPublisher;
    private final UniquenessGuard uniquenessGuard;
    private final JobMetrics metrics;
    private final int overlapDeferSeconds;

    JobOutcomeRecorder(JobRepository jobRepo, QueueRepository queueRepo, RetryService retryService,
                       JobEventPublisher eventPublisher,
                       UniquenessGuard uniquenessGuard, JobMetrics metrics, int overlapDeferSeconds) {
        this.jobRepo = jobRepo;
        this.queueRepo = queueRepo;
        this.retryService = retryService;
        this.eventPublisher = eventPublisher;
        this.uniquenessGuard = uniquenessGuard;
        this.metrics = metrics;
        this.overlapDeferSeconds = overlapDeferSeconds;
    }

    void success(JobEntity job, String result, long durationMs) {
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
        // A finished job is complete: the record should say so even if the handler never
        // reported a single step.
        current.setProgress(100.0);
        current.setUpdatedAt(Instant.now());

        if (!jobRepo.saveIfLeaseHeld(current, issuedLeaseToken)) {
            discarded(job, durationMs, "success");
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
    }

    void failure(JobEntity job, JobErrorCode errorCode, String errorMessage,
                 long durationMs, boolean timedOut) {
        String issuedLeaseToken = job.getLeaseToken();

        JobEntity current = jobRepo.findById(job.getId()).orElse(null);
        if (current == null) return;

        String status = retryService.handleFailureIfLeaseHeld(current, issuedLeaseToken,
                errorCode, errorMessage, durationMs);
        if (status == null) {
            discarded(job, durationMs, "failure");
            return;
        }

        if (timedOut) {
            metrics.recordCompleted(JobMetrics.Outcome.TIMEOUT, durationMs);
        } else {
            metrics.recordCompleted(
                    JobStatus.DLQ.name().equals(status)
                            ? JobMetrics.Outcome.DEAD_LETTER
                            : JobMetrics.Outcome.FAILED,
                    durationMs);
        }
    }

    /**
     * Records a failure that retrying cannot fix, without spending the attempt budget.
     */
    void terminalFailure(JobEntity job, JobErrorCode errorCode, String message, long durationMs) {
        String issuedLeaseToken = job.getLeaseToken();

        JobEntity current = jobRepo.findById(job.getId()).orElse(null);
        if (current == null) return;

        String status = retryService.handleTerminalFailureIfLeaseHeld(current, issuedLeaseToken,
                errorCode, message, durationMs);
        if (status == null) {
            discarded(job, durationMs, "terminal failure");
            return;
        }

        metrics.recordCompleted(JobMetrics.Outcome.FAILED, durationMs);
    }

    /**
     * Records the cancellation of a job whose handler has stopped. Fenced like every
     * other terminal write: only the worker holding the lease may end the attempt.
     */
    void cancellation(JobEntity job, long durationMs) {
        String issuedLeaseToken = job.getLeaseToken();

        JobEntity current = jobRepo.findById(job.getId()).orElse(null);
        if (current == null) return;

        current.setStatus(JobStatus.CANCELLED);
        current.setResult("Cancelled by " + (current.getCancelledBy() != null
                ? current.getCancelledBy() : "user"));
        current.setNextRunAt(null);
        current.setVisibleAt(null);
        current.setLeaseOwner(null);
        current.setLeaseToken(null);
        current.setCompletedAt(Instant.now());
        current.setUpdatedAt(Instant.now());

        if (!jobRepo.saveIfLeaseHeld(current, issuedLeaseToken)) {
            discarded(job, durationMs, "cancellation");
            return;
        }

        metrics.recordCompleted(JobMetrics.Outcome.CANCELLED, durationMs);
        eventPublisher.publish(JobEvent.JOB_CANCELLED, JobEventData.from(current));
    }

    /**
     * Puts a job back on the queue because its {@code uniqueKey} is busy.
     *
     * <p>Deliberately not routed through the retry path: nothing failed, so consuming an
     * attempt would burn the budget on a job that never had a chance to run. A job
     * overlapped many times in a row would otherwise land in the DLQ for being
     * "unlucky" rather than for being broken.</p>
     */
    void deferForOverlap(JobEntity job, long durationMs) {
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
            discarded(job, durationMs, "overlap deferral");
            return;
        }

        queueRepo.enqueue(current.getId(),
                current.getPriority() != null ? current.getPriority() : JobPriority.NORMAL,
                nextRunAt.toEpochMilli());
        metrics.recordCompleted(JobMetrics.Outcome.DEFERRED, durationMs);
        log.debug("Job {} deferred {}s because its unique key is in use",
                job.getId(), overlapDeferSeconds);
    }

    void releaseQuietly(JobEntity job, String owner) {
        try {
            uniquenessGuard.release(job.getUniqueKey(), owner);
        } catch (RuntimeException e) {
            // The TTL is the backstop. Losing the release only delays the key's reuse.
            log.warn("Failed to release unique key for job {}; relying on TTL", job.getId(), e);
        }
    }

    private void discarded(JobEntity job, long durationMs, String what) {
        metrics.recordFencedWriteRejected();
        metrics.recordCompleted(JobMetrics.Outcome.DISCARDED, durationMs);
        log.warn("Job {} {} discarded - lease was lost before the write", job.getId(), what);
    }
}
