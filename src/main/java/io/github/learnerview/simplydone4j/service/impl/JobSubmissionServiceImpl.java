package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.dto.JobResponse;
import io.github.learnerview.simplydone4j.dto.JobSubmissionRequest;
import io.github.learnerview.simplydone4j.dto.JobSubmissionResponse;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.event.JobEvent;
import io.github.learnerview.simplydone4j.event.JobEventData;
import io.github.learnerview.simplydone4j.event.JobEventPublisher;
import io.github.learnerview.simplydone4j.exception.JobNotFoundException;
import io.github.learnerview.simplydone4j.exception.QueueFullException;
import io.github.learnerview.simplydone4j.mapper.JobMapper;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.github.learnerview.simplydone4j.service.IdempotencyReservation;
import io.github.learnerview.simplydone4j.service.IdempotencyService;
import io.github.learnerview.simplydone4j.service.JobSignal;
import io.github.learnerview.simplydone4j.service.JobSubmissionService;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public final class JobSubmissionServiceImpl implements JobSubmissionService {
    private static final Logger log = LoggerFactory.getLogger(JobSubmissionServiceImpl.class);

    private final JobRepository jobRepo;
    private final QueueRepository queueRepo;
    private final SimplyDoneProperties config;
    private final JobMapper jobMapper;
    private final JobEventPublisher eventPublisher;
    private final IdempotencyService idempotencyService;
    private final Validator validator;
    private final JobSignal signal;
    private final long reservationRecordWaitMs;
    private final long admissionWaitMs;
    private final long resolvePollIntervalMs;
    private final long maxQueueDepth;
    private final long maxPayloadBytes;
    private final int defaultMaxAttempts;

    public JobSubmissionServiceImpl(JobRepository jobRepo, QueueRepository queueRepo,
                                     SimplyDoneProperties config,
                                     JobMapper jobMapper, JobEventPublisher eventPublisher,
                                     IdempotencyService idempotencyService, Validator validator) {
        this(jobRepo, queueRepo, config, jobMapper, eventPublisher,
                idempotencyService, validator, JobSignal.NOOP);
    }

    public JobSubmissionServiceImpl(JobRepository jobRepo, QueueRepository queueRepo,
                                     SimplyDoneProperties config,
                                     JobMapper jobMapper, JobEventPublisher eventPublisher,
                                     IdempotencyService idempotencyService, Validator validator,
                                     JobSignal signal) {
        this.jobRepo = jobRepo;
        this.queueRepo = queueRepo;
        this.config = config;
        this.jobMapper = jobMapper;
        this.eventPublisher = eventPublisher;
        this.idempotencyService = idempotencyService;
        this.validator = validator;
        this.signal = signal == null ? JobSignal.NOOP : signal;
        long recordWait = config == null ? 1_000L : config.getSubmission().getReservationRecordWaitMs();
        long admissionWait = config == null ? 1_000L : config.getSubmission().getAdmissionWaitMs();
        long pollInterval = config == null ? 25L : config.getSubmission().getResolvePollIntervalMs();
        this.reservationRecordWaitMs = Math.max(0L, recordWait);
        this.admissionWaitMs = Math.max(0L, admissionWait);
        this.resolvePollIntervalMs = Math.max(1L, pollInterval);
        this.maxQueueDepth = config == null ? 10_000L : Math.max(1L, config.getQueue().getMaxDepth());
        this.maxPayloadBytes = config == null
                ? 1_048_576L
                : Math.max(0L, config.getSubmission().getMaxPayloadBytes());
        this.defaultMaxAttempts = config == null ? 3 : Math.max(1, config.getRetry().getMaxAttempts());
    }

    @Override
    public JobSubmissionResponse submit(String producer, JobSubmissionRequest req) {
        var violations = validator.validate(req);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException("Validation failed: " + violations);
        }
        if (producer == null || producer.isBlank()) {
            throw new IllegalArgumentException("producer must not be null or blank");
        }

        // Read-only dedup before any admission limits are charged. A retry of an
        // already-accepted job must return the stored result even if the queue has since
        // filled; the authoritative acquireOrGetExisting below closes the check-then-act race
        // between this lookup and a competing submit.
        Optional<JobEntity> alreadyAccepted =
                jobRepo.findByProducerAndIdempotencyKey(producer, req.getIdempotencyKey());
        if (alreadyAccepted.isPresent()) {
            return acceptedDedupResponse(alreadyAccepted.get(), req.getIdempotencyKey());
        }

        String jobId = UUID.randomUUID().toString();
        Optional<IdempotencyReservation> existingReservation =
                idempotencyService.acquireOrGetExisting(producer, req.getIdempotencyKey(), jobId);

        if (existingReservation.isPresent()) {
            IdempotencyReservation reservation = existingReservation.get();
            JobEntity existing = jobRepo.findById(reservation.jobId()).orElse(null);
            if (existing == null && reservation.pending()) {
                // The holder is between taking the reservation and persisting the job.
                // Wait briefly for the record rather than releasing a reservation that is
                // actively being filled in.
                existing = waitForJobRecord(reservation.jobId(), reservationRecordWaitMs);
            }
            if (existing != null) {
                return acceptedDedupResponse(existing, req.getIdempotencyKey());
            }
            if (reservation.pending()) {
                // The owner is still in flight and the reservation is young. Releasing it
                // here lets a competing submission acquire the key while the owner is about
                // to persist its job, which is exactly the duplicate-job race the two-phase
                // reservation exists to close. Refuse rather than steal.
                log.warn("Idempotency reservation for key {} points at job {} which is not visible yet; "
                                + "the owning submission is presumably still in flight",
                        req.getIdempotencyKey(), reservation.jobId());
                throw new IllegalStateException("Job " + reservation.jobId()
                        + " for idempotencyKey " + req.getIdempotencyKey()
                        + " is still being created; retry the request");
            }

            // The idempotency lock outlived the job record it points at. The two have
            // independent TTLs, so this happens whenever a job passes its retention window
            // before its idempotency key expires. Release the stale lock and take a fresh
            // one rather than rejecting the submission until the key happens to expire --
            // otherwise this producer can never submit that key again. Only reachable for a
            // confirmed reservation; a pending one is an in-flight submission, not stale.
            log.warn("Idempotency key {} for producer {} points at expired job {}; releasing stale lock",
                    req.getIdempotencyKey(), producer, reservation.jobId());
            idempotencyService.releaseIfOwnedBy(producer, req.getIdempotencyKey(), reservation.jobId());

            Optional<IdempotencyReservation> reacquired = idempotencyService.acquireOrGetExisting(
                    producer, req.getIdempotencyKey(), jobId);
            if (reacquired.isPresent()) {
                IdempotencyReservation racedReservation = reacquired.get();
                JobEntity raced = jobRepo.findById(racedReservation.jobId()).orElse(null);
                if (raced == null && racedReservation.pending()) {
                    raced = waitForJobRecord(racedReservation.jobId(), reservationRecordWaitMs);
                }
                if (raced != null) {
                    return acceptedDedupResponse(raced, req.getIdempotencyKey());
                }
                if (racedReservation.pending()) {
                    throw new IllegalStateException("Job " + racedReservation.jobId()
                            + " for idempotencyKey " + req.getIdempotencyKey()
                            + " is still being created; retry the request");
                }
                throw new IllegalStateException("Could not resolve existing job for idempotencyKey: "
                        + req.getIdempotencyKey());
            }
        }

        JobPriority priority = jobMapper.parsePriority(req.getPriority());
        Instant now = Instant.now();
        Instant requestedNextRunAt = req.getNextRunAt();
        Instant nextRunAt = requestedNextRunAt == null || requestedNextRunAt.isBefore(now)
                ? now : requestedNextRunAt;

        JobEntity job;
        try {
            String payload = jobMapper.serializePayload(req.getPayload());
            enforcePayloadLimit(payload);
            job = JobEntity.builder()
                    .id(jobId)
                    .jobType(req.getJobType())
                    .producer(producer)
                    .idempotencyKey(req.getIdempotencyKey())
                    .status(JobStatus.QUEUED)
                    .priority(priority)
                    .payload(payload)
                    .nextRunAt(nextRunAt)
                    .timeoutSeconds(req.getTimeoutSeconds())
                    .maxAttempts(Math.max(1, req.getMaxAttempts() != null
                            ? req.getMaxAttempts() : defaultMaxAttempts))
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            jobRepo.save(job);
        } catch (RuntimeException e) {
            // The reservation is already owned by this attempt. Without releasing it, a
            // transient write failure leaves a pending key for its whole TTL and every
            // retry of this producer/key is refused as "still being created".
            compensateFailedSubmission(producer, req.getIdempotencyKey(), jobId, priority, "persist");
            throw e;
        }

        try {
            boolean promoted = idempotencyService.confirmReservation(producer, req.getIdempotencyKey(), jobId);
            if (!promoted) {
                // Not fatal: the job exists and future duplicates resolve it through the
                // pending marker. Failing the submission now would undo a persisted job
                // for no benefit.
                log.warn("Idempotency reservation for key {} was not promoted to job {}; "
                                + "duplicates will still resolve through the pending marker",
                        req.getIdempotencyKey(), jobId);
            }
        } catch (RuntimeException e) {
            log.warn("Could not promote idempotency reservation for key {} to job {}; "
                    + "duplicates will still resolve through the pending marker",
                    req.getIdempotencyKey(), jobId, e);
        }

        boolean enqueued;
        try {
            // Atomic capacity-checked admission: the depth test and the ZADD happen in one
            // Redis round trip, so concurrent submits cannot both observe a free slot.
            enqueued = queueRepo.enqueueIfUnderCapacity(jobId, priority, nextRunAt.toEpochMilli(),
                    maxQueueDepth);
        } catch (RuntimeException e) {
            // A connection failure does not prove the script did not run: the ZADD may have
            // committed before the client saw the error. Deleting the job here would then
            // leave a live queue entry pointing at a missing record and let a concurrent
            // duplicate report success for a job this call is about to erase. Confirm
            // membership when possible; otherwise leave the QUEUED record for orphan
            // reconciliation, which re-adds it when the queue write really was lost.
            if (queueContains(jobId, priority)) {
                log.warn("Queue admission for job {} reported failure but the job is present; "
                        + "treating the submission as accepted", jobId, e);
                enqueued = true;
            } else {
                log.error("Queue admission for job {} failed with an unknown outcome; leaving it "
                        + "QUEUED for orphan reconciliation", jobId, e);
                throw e;
            }
        }
        if (!enqueued) {
            // The script returned a definite "not admitted" result, so no queue write happened.
            compensateFailedSubmission(producer, req.getIdempotencyKey(), jobId, priority, "enqueue");
            throw new QueueFullException(maxQueueDepth);
        }

        JobEntity current;
        try {
            current = jobRepo.findById(jobId).orElse(null);
        } catch (RuntimeException e) {
            // The job is persisted and its queue write is known to be visible. A failed
            // verification read must not make submit throw after acceptance: callers treat
            // that as a failed submission and roll back work the job is about to perform.
            log.warn("Could not verify job {} after queue admission; returning it as accepted",
                    jobId, e);
            current = job;
        }
        if (current == null) {
            try {
                queueRepo.remove(jobId, priority);
            } catch (RuntimeException e) {
                log.warn("Could not remove vanished job {} from its queue", jobId, e);
            }
            try {
                idempotencyService.releaseIfOwnedBy(producer, req.getIdempotencyKey(), jobId);
            } catch (RuntimeException e) {
                log.warn("Could not release the reservation for vanished job {}", jobId, e);
            }
            throw new IllegalStateException("Job " + jobId
                    + " disappeared after queue admission; retry the request");
        }
        if (current.getStatus() != JobStatus.QUEUED) {
            try {
                queueRepo.remove(jobId, priority);
            } catch (RuntimeException e) {
                log.warn("Could not remove job {} from its queue after a concurrent status change",
                        jobId, e);
            }
            signalSafely();
            return dedupResponse(current);
        }

        signalSafely();
        log.info("Job submitted: {} type={} priority={}", jobId, req.getJobType(), priority);
        eventPublisher.publish(JobEvent.JOB_CREATED, JobEventData.from(job));

        return JobSubmissionResponse.builder()
                .jobId(jobId)
                .status(JobStatus.QUEUED.name())
                .jobType(req.getJobType())
                .priority(priority.name())
                .scheduledAt(nextRunAt)
                .build();
    }

    @Override
    public JobResponse getJob(String jobId) {
        JobEntity job = jobRepo.findById(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
        return jobMapper.toResponse(job);
    }

    @Override
    public void cancelJob(String jobId) {
        JobEntity job = jobRepo.findById(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
        if (job.getStatus() != JobStatus.QUEUED) {
            throw new IllegalArgumentException("Can only cancel QUEUED jobs, current: " + job.getStatus());
        }

        // Compare-and-set against the stored status rather than the status we just read.
        // A worker that claimed the job between our read and this call has moved it to
        // RUNNING; the CAS then loses and we must not overwrite a running job as cancelled.
        if (!jobRepo.transitionStatusIfCurrent(jobId, JobStatus.QUEUED, JobStatus.CANCELLED)) {
            JobEntity current = jobRepo.findById(jobId).orElse(null);
            throw new IllegalArgumentException("Can only cancel QUEUED jobs, current: "
                    + (current != null ? current.getStatus() : "MISSING"));
        }

        try {
            queueRepo.remove(jobId, job.getPriority());
        } catch (RuntimeException e) {
            log.warn("Could not remove cancelled job {} from its queue; the queued entry will be "
                    + "discarded when it is claimed", jobId, e);
        }
        JobEntity cancelled = jobRepo.findById(jobId).orElse(job);
        Instant cancelledAt = Instant.now();
        cancelled.setStatus(JobStatus.CANCELLED);
        cancelled.setNextRunAt(null);
        cancelled.setVisibleAt(null);
        cancelled.setLeaseOwner(null);
        cancelled.setLeaseToken(null);
        cancelled.setResult("Cancelled by user");
        cancelled.setCompletedAt(cancelledAt);
        cancelled.setUpdatedAt(cancelledAt);
        jobRepo.save(cancelled);
        eventPublisher.publish(JobEvent.JOB_CANCELLED, JobEventData.from(cancelled));
    }

    /**
     * Rejects payloads that would let one submission dominate Redis memory. The limit is
     * measured on the serialized UTF-8 form because that is what is stored in the job hash;
     * character count alone under-counts multi-byte payloads.
     */
    private void enforcePayloadLimit(String payload) {
        if (payload == null || maxPayloadBytes <= 0) {
            return;
        }
        int bytes = payload.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maxPayloadBytes) {
            throw new IllegalArgumentException("Payload is " + bytes
                    + " bytes, exceeding simplydone4j.submission.max-payload-bytes="
                    + maxPayloadBytes);
        }
    }

    /**
     * Polls the store for a job whose reservation exists but whose record has not been
     * written yet. Bounded so the request fails fast instead of waiting out a crashed owner.
     */
    private JobEntity waitForJobRecord(String jobId, long budgetMs) {
        long deadline = System.currentTimeMillis() + budgetMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(resolvePollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            JobEntity job = jobRepo.findById(jobId).orElse(null);
            if (job != null) {
                return job;
            }
        }
        return null;
    }

    /**
     * Best-effort undo of a job that could not be persisted or enqueued.
     *
     * <p>Both rollback steps are individually guarded: a failure to delete must not hide
     * the original failure the caller needs to see, and the idempotency release is a no-op
     * unless this submission still owns the key.
     */
    private void compensateFailedSubmission(String producer, String idempotencyKey, String jobId,
                                            JobPriority priority, String stage) {
        boolean cancelled = false;
        boolean readFailed = false;
        JobEntity current = null;
        for (int attempt = 0; attempt < 3 && !cancelled; attempt++) {
            try {
                cancelled = jobRepo.transitionStatusIfCurrent(
                        jobId, JobStatus.QUEUED, JobStatus.CANCELLED);
            } catch (RuntimeException e) {
                log.warn("Could not claim job {} for rollback after a failed {}; will retry",
                        jobId, stage, e);
            }
            if (cancelled) {
                break;
            }
            try {
                current = jobRepo.findById(jobId).orElse(null);
                readFailed = false;
            } catch (RuntimeException e) {
                current = null;
                readFailed = true;
                log.warn("Could not read job {} during rollback after a failed {}", jobId, stage, e);
            }
            if (!readFailed && current != null && current.getStatus() != JobStatus.QUEUED) {
                log.warn("Not rolling back job {} after a failed {} because it is already {}",
                        jobId, stage, current.getStatus());
                return;
            }
            if (!readFailed && current == null) {
                break;
            }
        }

        if (!cancelled) {
            if (readFailed) {
                log.error("Could not determine the state of job {} after a failed {}; leaving it "
                        + "and its idempotency reservation intact", jobId, stage);
                return;
            }
            if (current != null) {
                log.error("Could not atomically cancel job {} after a failed {}; leaving it for "
                        + "orphan recovery and retaining its idempotency reservation", jobId, stage);
                return;
            }
        } else {
            try {
                queueRepo.remove(jobId, priority);
            } catch (RuntimeException e) {
                log.warn("Could not remove job {} from its queue during rollback after a failed {}",
                        jobId, stage, e);
            }
        }

        try {
            jobRepo.delete(jobId);
        } catch (RuntimeException e) {
            log.error("Could not delete job {} after a failed {}; it may be stranded and must "
                    + "be cleaned up manually", jobId, stage, e);
        }
        try {
            idempotencyService.releaseIfOwnedBy(producer, idempotencyKey, jobId);
        } catch (RuntimeException e) {
            log.error("Could not release idempotency key {} for producer {} after a failed {}",
                    idempotencyKey, producer, stage, e);
        }
    }

    /**
     * A QUEUED record is not necessarily accepted yet: the submitter saves the job,
     * promotes the reservation, then performs the capacity-checked enqueue. A duplicate
     * arriving in that window would otherwise receive a success response for a job that
     * the owner is about to delete when the queue is full. Wait until the job is visibly
     * in its priority queue (or has moved past QUEUED), and refuse with a retryable error
     * if that does not happen within the bounded budget.
     */
    private JobSubmissionResponse acceptedDedupResponse(JobEntity existing, String idempotencyKey) {
        JobEntity accepted = waitForQueueAdmission(existing);
        if (accepted == null) {
            throw new IllegalStateException("Job " + existing.getId()
                    + " for idempotencyKey " + idempotencyKey
                    + " is still being accepted; retry the request");
        }
        return dedupResponse(accepted);
    }

    private JobEntity waitForQueueAdmission(JobEntity candidate) {
        if (candidate.getStatus() != JobStatus.QUEUED) {
            return candidate;
        }
        long deadline = System.currentTimeMillis() + admissionWaitMs;
        while (true) {
            JobEntity current = jobRepo.findById(candidate.getId()).orElse(null);
            if (current == null) {
                return null;
            }
            if (current.getStatus() != JobStatus.QUEUED) {
                return current;
            }
            if (queueRepo.contains(current.getId(), current.getPriority())) {
                return current;
            }
            if (System.currentTimeMillis() >= deadline) {
                return null;
            }
            try {
                Thread.sleep(resolvePollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    private boolean queueContains(String jobId, JobPriority priority) {
        try {
            return queueRepo.contains(jobId, priority);
        } catch (RuntimeException e) {
            log.warn("Could not determine whether job {} reached its queue", jobId, e);
            return false;
        }
    }

    private void signalSafely() {
        try {
            signal.signal();
        } catch (RuntimeException e) {
            log.warn("Could not signal the scheduler after a submission state change", e);
        }
    }

    private JobSubmissionResponse dedupResponse(JobEntity existing) {
        return JobSubmissionResponse.builder()
                .jobId(existing.getId())
                .status(existing.getStatus().name())
                .jobType(existing.getJobType())
                .priority(existing.getPriority().name())
                .scheduledAt(existing.getNextRunAt())
                .build();
    }
}
