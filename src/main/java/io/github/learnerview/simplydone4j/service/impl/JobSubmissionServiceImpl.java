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
import io.github.learnerview.simplydone4j.exception.JobSubmissionInProgressException;
import io.github.learnerview.simplydone4j.exception.QueueFullException;
import io.github.learnerview.simplydone4j.mapper.JobMapper;
import io.github.learnerview.simplydone4j.metrics.JobMetrics;
import io.github.learnerview.simplydone4j.model.CancelOutcome;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.github.learnerview.simplydone4j.repository.IdempotencyStore;
import io.github.learnerview.simplydone4j.util.Uuid7Generator;
import io.github.learnerview.simplydone4j.service.JobSubmissionService;
import io.github.learnerview.simplydone4j.api.spi.AdmissionDecision;
import io.github.learnerview.simplydone4j.api.spi.AdmissionPolicy;
import io.github.learnerview.simplydone4j.api.spi.AdmissionRequest;
import io.github.learnerview.simplydone4j.exception.AdmissionRejectedException;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public final class JobSubmissionServiceImpl implements JobSubmissionService {
    private static final Logger log = LoggerFactory.getLogger(JobSubmissionServiceImpl.class);

    private final JobRepository jobRepo;
    private final QueueRepository queueRepo;
    private final AdmissionPolicy admissionPolicy;
    private final SimplyDoneProperties config;
    private final JobMapper jobMapper;
    private final JobEventPublisher eventPublisher;
    private final IdempotencyStore idempotencyStore;
    private final Uuid7Generator idGenerator = new Uuid7Generator();
    private final JobMetrics metrics;
    private final Validator validator;

    public JobSubmissionServiceImpl(JobRepository jobRepo, QueueRepository queueRepo,
                                     AdmissionPolicy admissionPolicy, SimplyDoneProperties config,
                                     JobMapper jobMapper, JobEventPublisher eventPublisher,
                                     IdempotencyStore idempotencyStore, Validator validator) {
        this(jobRepo, queueRepo, admissionPolicy, config, jobMapper, eventPublisher, idempotencyStore,
                validator, JobMetrics.NOOP);
    }

    public JobSubmissionServiceImpl(JobRepository jobRepo, QueueRepository queueRepo,
                                     AdmissionPolicy admissionPolicy, SimplyDoneProperties config,
                                     JobMapper jobMapper, JobEventPublisher eventPublisher,
                                     IdempotencyStore idempotencyStore, Validator validator,
                                     JobMetrics metrics) {
        this.jobRepo = jobRepo;
        this.queueRepo = queueRepo;
        this.admissionPolicy = admissionPolicy == null ? AdmissionPolicy.allowAll() : admissionPolicy;
        this.config = config;
        this.jobMapper = jobMapper;
        this.eventPublisher = eventPublisher;
        this.idempotencyStore = idempotencyStore;
        this.validator = validator;
        this.metrics = metrics == null ? JobMetrics.NOOP : metrics;
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

        // Read-only dedup before the policy is consulted. A retry of an already-accepted
        // job must return the stored result even if the queue has since filled or the
        // policy has since changed its mind; asking the policy first rejects a duplicate
        // that was in fact accepted. The authoritative claim below still closes the
        // check-then-act race between this lookup and a competing submit.
        //
        // The key namespace is resolved through the idempotency store, which is the only
        // owner of it. The job repository used to read the same key itself, which meant two
        // classes had to agree on the key's name for a duplicate to be detected.
        Optional<JobEntity> alreadyAccepted = idempotencyStore.lookup(producer, req.getIdempotencyKey())
                .flatMap(jobRepo::findById);
        if (alreadyAccepted.isPresent()) {
            return dedupResponse(alreadyAccepted.get());
        }

        JobPriority priority = jobMapper.parsePriority(req.getPriority());
        AdmissionDecision decision = admissionPolicy.decide(new AdmissionRequest(
                producer, req.getJobType(), priority, req.getPayload(), Instant.now()));
        if (!decision.admitted()) {
            throw new AdmissionRejectedException(decision.reason(), decision.retryAfter());
        }

        String jobId = idGenerator.next();
        Optional<IdempotencyStore.Reservation> reservation =
                idempotencyStore.claim(producer, req.getIdempotencyKey(), jobId);

        if (reservation.isPresent()) {
            IdempotencyStore.Reservation existing = reservation.get();
            if (existing.pending()) {
                JobSubmissionResponse adopted = adoptCommittedSubmission(
                        producer, req.getIdempotencyKey(), existing);
                if (adopted != null) {
                    return adopted;
                }
                // The original submitter is still between reserving the key and
                // committing it. Returning its job id here would hand out a handle the
                // original may still roll back, so the duplicate is told to retry.
                throw new JobSubmissionInProgressException(req.getIdempotencyKey(),
                        Duration.ofSeconds(1));
            }

            JobEntity existingJob = jobRepo.findById(existing.jobId()).orElse(null);
            if (existingJob != null) {
                return dedupResponse(existingJob);
            }

            // The idempotency lock outlived the job record it points at. The two have
            // independent TTLs, so this happens whenever a job passes its retention window
            // before its idempotency key expires. Release the stale lock and take a fresh
            // one rather than rejecting the submission until the key happens to expire --
            // otherwise this producer can never submit that key again.
            log.warn("Idempotency key {} for producer {} points at expired job {}; releasing stale lock",
                    req.getIdempotencyKey(), producer, existing.jobId());
            idempotencyStore.releaseIfOwnedBy(producer, req.getIdempotencyKey(), existing.jobId());

            Optional<IdempotencyStore.Reservation> reacquired = idempotencyStore.claim(
                    producer, req.getIdempotencyKey(), jobId);
            if (reacquired.isPresent()) {
                IdempotencyStore.Reservation raced = reacquired.get();
                if (raced.pending()) {
                    throw new JobSubmissionInProgressException(req.getIdempotencyKey(),
                            Duration.ofSeconds(1));
                }
                JobEntity racedJob = jobRepo.findById(raced.jobId()).orElse(null);
                if (racedJob != null) {
                    return dedupResponse(racedJob);
                }
                throw new IllegalStateException("Could not resolve existing job for idempotencyKey: "
                        + req.getIdempotencyKey());
            }
        }

        Instant nextRunAt = req.getNextRunAt() != null ? req.getNextRunAt() : Instant.now();
        Instant now = Instant.now();

        JobEntity job = JobEntity.builder()
                .id(jobId)
                .jobType(req.getJobType())
                .producer(producer)
                .idempotencyKey(req.getIdempotencyKey())
                .uniqueKey(req.getUniqueKey())
                .status(JobStatus.QUEUED)
                .priority(priority)
                .payload(jobMapper.serializePayload(req.getPayload()))
                .nextRunAt(nextRunAt)
                .timeoutSeconds(req.getTimeoutSeconds())
                .callbackUrl(req.getCallbackUrl())
                .maxAttempts(req.getMaxAttempts() != null ? req.getMaxAttempts() : config.getRetry().getMaxAttempts())
                .createdAt(now)
                .updatedAt(now)
                .build();

        jobRepo.save(job);

        boolean enqueued;
        try {
            // Atomic capacity-checked admission: the depth test and the ZADD happen in one
            // Redis round trip, so concurrent submits cannot both observe a free slot.
            enqueued = queueRepo.enqueueIfUnderCapacity(jobId, priority, nextRunAt.toEpochMilli(),
                    config.getQueue().getMaxDepth());
        } catch (RuntimeException e) {
            // The job is already persisted and the idempotency key is held. Leaving them
            // behind strands a QUEUED record no queue will ever hand to a worker and burns
            // the key so the producer can never resubmit. Undo both, then surface the
            // infrastructure failure.
            compensateFailedEnqueue(producer, req.getIdempotencyKey(), jobId);
            throw e;
        }
        if (!enqueued) {
            compensateFailedEnqueue(producer, req.getIdempotencyKey(), jobId);
            throw new QueueFullException(config.getQueue().getMaxDepth());
        }

        if (!idempotencyStore.commit(producer, req.getIdempotencyKey(), jobId)) {
            // The job is durable and queued, so the submission did happen. The
            // reservation will expire on its own; a later duplicate will then either
            // adopt this job or be told to retry. Do not fail the caller for a
            // bookkeeping write that lost a race after the work was admitted.
            log.error("Job {} was enqueued but its idempotency reservation could not be committed",
                    jobId);
        }

        log.info("Job submitted: {} type={} priority={}", jobId, req.getJobType(), priority);
        metrics.recordSubmitted(priority);
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

    /**
     * {@inheritDoc}
     *
     * <p>Cancelling is one atomic compare-and-set in the store, not a status flip followed
     * by a queue removal and an unfenced save. The old shape had two problems: the status
     * flip and the save were separate writes, so a worker that claimed the job in between
     * could have its {@code RUNNING} transition overwritten by the cancel, and the save was
     * unfenced, so it could land after the job had been reaped and resurrect it.</p>
     *
     * <p>A job that is already running cannot be un-run. Its cancellation is a request: the
     * worker notices the flag, stops the handler and writes the terminal state. The
     * difference is reported to the caller through {@link CancelOutcome}.</p>
     */
    @Override
    public void cancelJob(String jobId) {
        CancelOutcome outcome = jobRepo.requestCancellation(jobId, "user");

        switch (outcome) {
            case NOT_FOUND -> throw new JobNotFoundException(jobId);

            case NOT_CANCELLABLE -> {
                JobEntity current = jobRepo.findById(jobId).orElse(null);
                throw new IllegalArgumentException("Cannot cancel a job in status "
                        + (current != null ? current.getStatus() : "MISSING"));
            }

            case REQUESTED -> {
                // The worker owns the terminal transition for a job that is already
                // running: it stops the handler and records the cancellation itself.
                log.info("Cancellation requested for running job {}; the worker will stop it", jobId);
            }

            case CANCELLED -> {
                JobEntity job = jobRepo.findById(jobId).orElse(null);
                if (job != null && job.getPriority() != null) {
                    // The job is already terminal in the store, so a job that is still
                    // listed in the ready queue is only a leftover entry. Removing it
                    // keeps the queue depth honest; if it fails, the next claim of that
                    // entry fails on the status and drops it anyway.
                    queueRepo.remove(jobId, job.getPriority());
                }
                if (job != null) {
                    eventPublisher.publish(JobEvent.JOB_CANCELLED, JobEventData.from(job));
                }
                log.info("Job {} cancelled", jobId);
            }
        }
    }

    /**
     * Finishes a pending submission whose job is already durable and visible to the
     * scheduler.
     *
     * <p>This is the safe recovery path for a submitter that died after enqueueing but
     * before committing the key: the queue entry proves the job was admitted, so the
     * duplicate can adopt it instead of waiting for the reservation to expire. A job
     * that is still {@code QUEUED} without a queue entry is deliberately not adopted --
     * the original may yet enqueue it, or may roll it back.</p>
     */
    private JobSubmissionResponse adoptCommittedSubmission(
            String producer, String idempotencyKey, IdempotencyStore.Reservation reservation) {
        JobEntity pendingJob = jobRepo.findById(reservation.jobId()).orElse(null);
        if (pendingJob == null || pendingJob.getPriority() == null) {
            return null;
        }
        if (pendingJob.getStatus() == JobStatus.QUEUED
                && !queueRepo.contains(pendingJob.getId(), pendingJob.getPriority())) {
            return null;
        }
        if (!idempotencyStore.commit(producer, idempotencyKey, reservation.jobId())) {
            return null;
        }
        log.info("Adopted in-flight idempotent submission {} for producer {}",
                reservation.jobId(), producer);
        return dedupResponse(pendingJob);
    }

    /**
     * Best-effort undo of a saved job whose enqueue did not succeed.
     *
     * <p>Both rollback steps are individually guarded: a failure to delete must not hide
     * the enqueue failure the caller needs to see, and the idempotency release is a no-op
     * unless this submission still owns the key.
     */
    private void compensateFailedEnqueue(String producer, String idempotencyKey, String jobId) {
        try {
            jobRepo.delete(jobId);
        } catch (RuntimeException e) {
            log.error("Could not delete job {} after a failed enqueue; it is stranded as QUEUED "
                    + "with no queue entry and must be cleaned up manually", jobId, e);
        }
        try {
            idempotencyStore.releaseIfOwnedBy(producer, idempotencyKey, jobId);
        } catch (RuntimeException e) {
            log.error("Could not release idempotency key {} for producer {} after a failed enqueue",
                    idempotencyKey, producer, e);
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
