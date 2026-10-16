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
import io.github.learnerview.simplydone4j.metrics.JobMetrics;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.github.learnerview.simplydone4j.service.IdempotencyService;
import io.github.learnerview.simplydone4j.service.JobSubmissionService;
import io.github.learnerview.simplydone4j.service.RateLimiterService;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public final class JobSubmissionServiceImpl implements JobSubmissionService {
    private static final Logger log = LoggerFactory.getLogger(JobSubmissionServiceImpl.class);

    private final JobRepository jobRepo;
    private final QueueRepository queueRepo;
    private final RateLimiterService rateLimiter;
    private final SimplyDoneProperties config;
    private final JobMapper jobMapper;
    private final JobEventPublisher eventPublisher;
    private final IdempotencyService idempotencyService;
    private final JobMetrics metrics;
    private final Validator validator;

    public JobSubmissionServiceImpl(JobRepository jobRepo, QueueRepository queueRepo,
                                     RateLimiterService rateLimiter, SimplyDoneProperties config,
                                     JobMapper jobMapper, JobEventPublisher eventPublisher,
                                     IdempotencyService idempotencyService, Validator validator) {
        this(jobRepo, queueRepo, rateLimiter, config, jobMapper, eventPublisher, idempotencyService,
                validator, JobMetrics.NOOP);
    }

    public JobSubmissionServiceImpl(JobRepository jobRepo, QueueRepository queueRepo,
                                     RateLimiterService rateLimiter, SimplyDoneProperties config,
                                     JobMapper jobMapper, JobEventPublisher eventPublisher,
                                     IdempotencyService idempotencyService, Validator validator,
                                     JobMetrics metrics) {
        this.jobRepo = jobRepo;
        this.queueRepo = queueRepo;
        this.rateLimiter = rateLimiter;
        this.config = config;
        this.jobMapper = jobMapper;
        this.eventPublisher = eventPublisher;
        this.idempotencyService = idempotencyService;
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

        // Read-only dedup before any limit is charged. A retry of an already-accepted job
        // must return the stored result even if the queue has since filled or the producer
        // is now rate limited; charging a limit first rejects a duplicate that was in fact
        // accepted. The authoritative acquireOrGetExisting below still closes the
        // check-then-act race between this lookup and a competing submit.
        Optional<JobEntity> alreadyAccepted =
                jobRepo.findByProducerAndIdempotencyKey(producer, req.getIdempotencyKey());
        if (alreadyAccepted.isPresent()) {
            return dedupResponse(alreadyAccepted.get());
        }

        rateLimiter.checkRateLimit(producer);

        String jobId = UUID.randomUUID().toString();
        Optional<String> existingJobIdOpt = idempotencyService.acquireOrGetExisting(producer, req.getIdempotencyKey(), jobId);

        if (existingJobIdOpt.isPresent()) {
            String existingJobId = existingJobIdOpt.get();
            JobEntity existing = jobRepo.findById(existingJobId).orElse(null);
            if (existing != null) {
                return dedupResponse(existing);
            }

            // The idempotency lock outlived the job record it points at. The two have
            // independent TTLs, so this happens whenever a job passes its retention window
            // before its idempotency key expires. Release the stale lock and take a fresh
            // one rather than rejecting the submission until the key happens to expire --
            // otherwise this producer can never submit that key again.
            log.warn("Idempotency key {} for producer {} points at expired job {}; releasing stale lock",
                    req.getIdempotencyKey(), producer, existingJobId);
            idempotencyService.releaseIfOwnedBy(producer, req.getIdempotencyKey(), existingJobId);

            Optional<String> reacquired = idempotencyService.acquireOrGetExisting(
                    producer, req.getIdempotencyKey(), jobId);
            if (reacquired.isPresent()) {
                JobEntity raced = jobRepo.findById(reacquired.get()).orElse(null);
                if (raced != null) {
                    return dedupResponse(raced);
                }
                throw new IllegalStateException("Could not resolve existing job for idempotencyKey: "
                        + req.getIdempotencyKey());
            }
        }

        JobPriority priority = jobMapper.parsePriority(req.getPriority());
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

        queueRepo.remove(jobId, job.getPriority());
        job.setStatus(JobStatus.CANCELLED);
        job.setVisibleAt(null);
        job.setLeaseOwner(null);
        job.setLeaseToken(null);
        job.setResult("Cancelled by user");
        job.setCompletedAt(Instant.now());
        job.setUpdatedAt(Instant.now());
        jobRepo.save(job);
        eventPublisher.publish(JobEvent.JOB_CANCELLED, JobEventData.from(job));
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
            idempotencyService.releaseIfOwnedBy(producer, idempotencyKey, jobId);
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
