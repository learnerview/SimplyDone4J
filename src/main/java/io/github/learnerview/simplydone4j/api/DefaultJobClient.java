package io.github.learnerview.simplydone4j.api;

import io.github.learnerview.simplydone4j.api.spi.PayloadCodec;
import io.github.learnerview.simplydone4j.dto.JobSubmissionRequest;
import io.github.learnerview.simplydone4j.dto.JobSubmissionResponse;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.event.JobEvent;
import io.github.learnerview.simplydone4j.event.JobEventData;
import io.github.learnerview.simplydone4j.event.JobEventPublisher;
import io.github.learnerview.simplydone4j.mapper.JobMapper;
import io.github.learnerview.simplydone4j.model.CancelOutcome;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.github.learnerview.simplydone4j.service.DeadLetterService;
import io.github.learnerview.simplydone4j.service.JobSubmissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Default implementation of {@link JobClient}.
 */
public final class DefaultJobClient implements JobClient {

    private static final Logger log = LoggerFactory.getLogger(DefaultJobClient.class);

    private final JobSubmissionService submissionService;
    private final JobRepository jobRepo;
    private final QueueRepository queueRepo;
    private final DeadLetterService deadLetterService;
    private final JobMapper jobMapper;
    private final PayloadCodec payloadCodec;
    private final JobEventPublisher eventPublisher;

    public DefaultJobClient(JobSubmissionService submissionService,
                            JobRepository jobRepo,
                            QueueRepository queueRepo,
                            DeadLetterService deadLetterService,
                            JobMapper jobMapper,
                            PayloadCodec payloadCodec,
                            JobEventPublisher eventPublisher) {
        this.submissionService = Objects.requireNonNull(submissionService, "submissionService");
        this.jobRepo = Objects.requireNonNull(jobRepo, "jobRepo");
        this.queueRepo = Objects.requireNonNull(queueRepo, "queueRepo");
        this.deadLetterService = Objects.requireNonNull(deadLetterService, "deadLetterService");
        this.jobMapper = Objects.requireNonNull(jobMapper, "jobMapper");
        this.payloadCodec = Objects.requireNonNull(payloadCodec, "payloadCodec");
        this.eventPublisher = eventPublisher;
    }

    @Override
    public JobHandle submit(JobSubmission submission) {
        Objects.requireNonNull(submission, "submission must not be null");

        JobSubmissionRequest req = new JobSubmissionRequest();
        req.setJobType(submission.getJobType());
        req.setIdempotencyKey(submission.getIdempotencyKey());
        req.setUniqueKey(submission.getUniqueKey());
        if (submission.getPriority() != null) {
            req.setPriority(submission.getPriority().name());
        }
        if (submission.getRunAt() != null) {
            req.setNextRunAt(submission.getRunAt());
        }
        if (submission.getMaxAttempts() != null) {
            req.setMaxAttempts(submission.getMaxAttempts());
        }
        if (submission.getTimeoutSeconds() != null) {
            req.setTimeoutSeconds(submission.getTimeoutSeconds());
        }
        req.setCallbackUrl(submission.getCallbackUrl());

        Map<String, Object> payloadMap = payloadCodec.toMap(submission.getPayload());
        req.setPayload(payloadMap);

        JobSubmissionResponse res = submissionService.submit(submission.getProducer(), req);
        return new JobHandle(res.getJobId(), res.getJobType(), res.getStatus());
    }

    @Override
    public Optional<JobView> get(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            return Optional.empty();
        }
        return jobRepo.findById(jobId).map(this::toJobView);
    }

    @Override
    public CancelOutcome cancel(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            return CancelOutcome.NOT_FOUND;
        }
        CancelOutcome outcome = jobRepo.requestCancellation(jobId, "user");
        if (outcome == CancelOutcome.CANCELLED) {
            JobEntity job = jobRepo.findById(jobId).orElse(null);
            if (job != null && job.getPriority() != null) {
                queueRepo.remove(jobId, job.getPriority());
            }
            if (job != null && eventPublisher != null) {
                if (job.getStatus() == null) {
                    job.setStatus(JobStatus.CANCELLED);
                }
                eventPublisher.publish(JobEvent.JOB_CANCELLED, JobEventData.from(job));
            }
        }
        return outcome;
    }

    @Override
    public boolean retry(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            return false;
        }
        try {
            deadLetterService.requeue(jobId);
            return true;
        } catch (Exception e) {
            log.warn("Could not retry job {}: {}", jobId, e);
            return false;
        }
    }

    @Override
    public List<JobView> listByStatus(JobStatus status) {
        if (status == null) {
            return Collections.emptyList();
        }
        return jobRepo.findByStatus(status).stream()
                .map(this::toJobView)
                .toList();
    }

    @Override
    public long countByStatus(JobStatus status) {
        if (status == null) {
            return 0L;
        }
        return jobRepo.countByStatus(status);
    }

    private JobView toJobView(JobEntity entity) {
        Map<String, Object> payload = jobMapper.deserializePayload(entity.getPayload());
        return JobView.builder()
                .id(entity.getId())
                .jobType(entity.getJobType())
                .producer(entity.getProducer())
                .idempotencyKey(entity.getIdempotencyKey())
                .uniqueKey(entity.getUniqueKey())
                .status(entity.getStatus())
                .priority(entity.getPriority())
                .payload(payload)
                .result(entity.getResult())
                .errorCode(entity.getErrorCode())
                .progress(entity.getProgress())
                .progressMessage(entity.getProgressMessage())
                .cancelRequested(Boolean.TRUE.equals(entity.getCancelRequested()))
                .cancelledBy(entity.getCancelledBy())
                .nextRunAt(entity.getNextRunAt())
                .startedAt(entity.getStartedAt())
                .completedAt(entity.getCompletedAt())
                .attemptCount(entity.getAttemptCount())
                .maxAttempts(entity.getMaxAttempts())
                .timeoutSeconds(entity.getTimeoutSeconds())
                .callbackUrl(entity.getCallbackUrl())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .codec(payloadCodec)
                .build();
    }
}
