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
import io.github.learnerview.simplydone4j.service.JobSubmissionService;
import io.github.learnerview.simplydone4j.service.RateLimiterService;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class JobSubmissionServiceImpl implements JobSubmissionService {
    private static final Logger log = LoggerFactory.getLogger(JobSubmissionServiceImpl.class);
    private static final JobPriority[] PRIORITIES = JobPriority.values();
    private static final String IDEMPOTENCY_PREFIX = "simplydone4j:idempotency:";

    private final JobRepository jobRepo;
    private final QueueRepository queueRepo;
    private final RateLimiterService rateLimiter;
    private final SimplyDoneProperties config;
    private final JobMapper jobMapper;
    private final JobEventPublisher eventPublisher;
    private final StringRedisTemplate redis;
    private final Validator validator;

    public JobSubmissionServiceImpl(JobRepository jobRepo, QueueRepository queueRepo,
                                     RateLimiterService rateLimiter, SimplyDoneProperties config,
                                     JobMapper jobMapper, JobEventPublisher eventPublisher,
                                     StringRedisTemplate redis, Validator validator) {
        this.jobRepo = jobRepo;
        this.queueRepo = queueRepo;
        this.rateLimiter = rateLimiter;
        this.config = config;
        this.jobMapper = jobMapper;
        this.eventPublisher = eventPublisher;
        this.redis = redis;
        this.validator = validator;
    }

    @Override
    public JobSubmissionResponse submit(String producer, JobSubmissionRequest req) {
        var violations = validator.validate(req);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException("Validation failed: " + violations);
        }

        rateLimiter.checkRateLimit(producer);

        if (totalQueueDepth() >= config.getQueue().getMaxDepth()) {
            throw new QueueFullException(config.getQueue().getMaxDepth());
        }

        String idempotencyKey = idempotencyRedisKey(producer, req.getIdempotencyKey());
        Boolean acquired = redis.opsForValue().setIfAbsent(idempotencyKey, "pending",
                java.time.Duration.ofHours(1));
        if (Boolean.FALSE.equals(acquired)) {
            String jobId = redis.opsForValue().get(idempotencyKey);
            if (jobId != null && !"pending".equals(jobId)) {
                JobEntity existing = jobRepo.findById(jobId).orElse(null);
                if (existing != null) {
                    return JobSubmissionResponse.builder()
                            .jobId(existing.getId())
                            .status(existing.getStatus().name())
                            .jobType(existing.getJobType())
                            .priority(existing.getPriority().name())
                            .scheduledAt(existing.getNextRunAt())
                            .build();
                }
            }
            throw new IllegalStateException("Duplicate submission detected for idempotencyKey: "
                    + req.getIdempotencyKey());
        }

        JobPriority priority = jobMapper.parsePriority(req.getPriority());
        Instant nextRunAt = req.getNextRunAt() != null ? req.getNextRunAt() : Instant.now();
        String jobId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        JobEntity job = JobEntity.builder()
                .id(jobId)
                .jobType(req.getJobType())
                .producer(producer)
                .idempotencyKey(req.getIdempotencyKey())
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
        queueRepo.enqueue(jobId, priority, nextRunAt.toEpochMilli());

        redis.opsForValue().set(idempotencyKey, jobId, java.time.Duration.ofHours(1));

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
        if (job.getStatus() == JobStatus.QUEUED) {
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
        } else {
            throw new IllegalArgumentException("Can only cancel QUEUED jobs, current: " + job.getStatus());
        }
    }

    private long totalQueueDepth() {
        long total = 0L;
        for (JobPriority p : PRIORITIES) {
            total += queueRepo.queueSize(p);
        }
        return total;
    }

    private static String idempotencyRedisKey(String producer, String idempotencyKey) {
        return IDEMPOTENCY_PREFIX + producer + ':' + idempotencyKey;
    }
}
