package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.dto.JobResponse;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.event.JobEvent;
import io.github.learnerview.simplydone4j.event.JobEventData;
import io.github.learnerview.simplydone4j.event.JobEventPublisher;
import io.github.learnerview.simplydone4j.exception.JobNotFoundException;
import io.github.learnerview.simplydone4j.exception.QueueFullException;
import io.github.learnerview.simplydone4j.mapper.JobMapper;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.github.learnerview.simplydone4j.service.DeadLetterService;
import io.github.learnerview.simplydone4j.service.JobSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

public final class DeadLetterServiceImpl implements DeadLetterService {
    private static final Logger log = LoggerFactory.getLogger(DeadLetterServiceImpl.class);

    private final JobRepository jobRepo;
    private final QueueRepository queueRepo;
    private final JobMapper jobMapper;
    private final JobEventPublisher eventPublisher;
    private final JobSignal signal;
    private final long maxQueueDepth;
    private final int defaultMaxAttempts;

    public DeadLetterServiceImpl(JobRepository jobRepo, QueueRepository queueRepo,
                                 JobMapper jobMapper, JobEventPublisher eventPublisher,
                                 JobSignal signal, SimplyDoneProperties config) {
        this.jobRepo = jobRepo;
        this.queueRepo = queueRepo;
        this.jobMapper = jobMapper;
        this.eventPublisher = eventPublisher;
        this.signal = signal == null ? JobSignal.NOOP : signal;
        this.maxQueueDepth = config == null ? Long.MAX_VALUE
                : Math.max(1L, config.getQueue().getMaxDepth());
        this.defaultMaxAttempts = config == null ? 3
                : Math.max(1, config.getRetry().getMaxAttempts());
    }

    @Override
    public List<JobResponse> list(int limit) {
        return jobRepo.findByStatus(JobStatus.DLQ, Math.max(1, limit)).stream()
                .map(jobMapper::toResponse)
                .toList();
    }

    @Override
    public long count() {
        return jobRepo.countByStatus(JobStatus.DLQ);
    }

    @Override
    public JobResponse replay(String jobId) {
        JobEntity original = requireDeadLetter(jobId);
        Instant now = Instant.now();
        JobEntity replayed = copy(original);
        replayed.setStatus(JobStatus.QUEUED);
        replayed.setResult(null);
        replayed.setAttemptCount(0);
        replayed.setMaxAttempts(original.getMaxAttempts() > 0
                ? original.getMaxAttempts() : defaultMaxAttempts);
        replayed.setStartedAt(null);
        replayed.setCompletedAt(null);
        replayed.setLeaseOwner(null);
        replayed.setLeaseToken(null);
        replayed.setVisibleAt(null);
        replayed.setNextRunAt(now);
        replayed.setUpdatedAt(now);

        if (!jobRepo.saveIfStatus(replayed, JobStatus.DLQ)) {
            throw new IllegalStateException("Job " + jobId
                    + " is no longer in the dead-letter queue");
        }

        boolean admitted;
        try {
            admitted = queueRepo.enqueueIfUnderCapacity(jobId, replayed.getPriority(),
                    now.toEpochMilli(), maxQueueDepth);
        } catch (RuntimeException e) {
            if (queueContains(jobId, replayed)) {
                log.warn("Replay admission for job {} reported failure but its queue entry "
                        + "is visible; treating the replay as accepted", jobId, e);
                admitted = true;
            } else {
                log.error("Replay admission for job {} failed with an unknown outcome; leaving it "
                        + "QUEUED for orphan reconciliation", jobId, e);
                throw e;
            }
        }

        if (!admitted) {
            boolean restored = jobRepo.saveIfStatus(original, JobStatus.QUEUED);
            if (!restored) {
                log.error("Could not restore job {} to DLQ after queue admission failed; "
                        + "leaving it QUEUED for orphan reconciliation", jobId);
            }
            throw new QueueFullException(maxQueueDepth);
        }

        signalSafely();
        eventPublisher.publish(JobEvent.JOB_REPLAYED, JobEventData.builder()
                .jobId(replayed.getId())
                .jobType(replayed.getJobType())
                .producer(replayed.getProducer())
                .status(JobStatus.QUEUED.name())
                .priority(replayed.getPriority() != null ? replayed.getPriority().name() : null)
                .attempt(0)
                .maxAttempts(replayed.getMaxAttempts())
                .timestamp(now)
                .build());
        log.info("Dead-lettered job {} replayed", jobId);
        return jobMapper.toResponse(replayed);
    }

    @Override
    public void discard(String jobId) {
        JobEntity original = requireDeadLetter(jobId);

        // Move out of DLQ atomically before deleting. If a replay wins the race, this
        // transition loses and we must not delete a job that is now queued.
        if (!jobRepo.transitionStatusIfCurrent(jobId, JobStatus.DLQ, JobStatus.FAILED)) {
            throw new IllegalStateException("Job " + jobId
                    + " is no longer in the dead-letter queue");
        }

        try {
            jobRepo.delete(jobId);
        } catch (RuntimeException e) {
            // Keep an auditable terminal record instead of leaving a half-deleted job.
            jobRepo.saveIfStatus(original, JobStatus.FAILED);
            throw e;
        }

        eventPublisher.publish(JobEvent.JOB_DISCARDED, JobEventData.builder()
                .jobId(original.getId())
                .jobType(original.getJobType())
                .producer(original.getProducer())
                .status("DISCARDED")
                .priority(original.getPriority() != null ? original.getPriority().name() : null)
                .timestamp(Instant.now())
                .build());
        log.info("Dead-lettered job {} discarded", jobId);
    }

    private JobEntity requireDeadLetter(String jobId) {
        JobEntity job = jobRepo.findById(jobId)
                .orElseThrow(() -> new JobNotFoundException(jobId));
        if (job.getStatus() != JobStatus.DLQ) {
            throw new IllegalArgumentException("Job " + jobId
                    + " is not in the dead-letter queue, current: " + job.getStatus());
        }
        return job;
    }

    private boolean queueContains(String jobId, JobEntity job) {
        try {
            return job.getPriority() != null && queueRepo.contains(jobId, job.getPriority());
        } catch (RuntimeException e) {
            log.warn("Could not determine whether replayed job {} reached its queue", jobId, e);
            return false;
        }
    }

    private void signalSafely() {
        try {
            signal.signal();
        } catch (RuntimeException e) {
            log.warn("Could not signal the scheduler after a dead-letter replay", e);
        }
    }

    private static JobEntity copy(JobEntity source) {
        return JobEntity.builder()
                .id(source.getId())
                .jobType(source.getJobType())
                .producer(source.getProducer())
                .idempotencyKey(source.getIdempotencyKey())
                .status(source.getStatus())
                .priority(source.getPriority())
                .payload(source.getPayload())
                .result(source.getResult())
                .nextRunAt(source.getNextRunAt())
                .visibleAt(source.getVisibleAt())
                .leaseOwner(source.getLeaseOwner())
                .leaseToken(source.getLeaseToken())
                .timeoutSeconds(source.getTimeoutSeconds())
                .startedAt(source.getStartedAt())
                .completedAt(source.getCompletedAt())
                .attemptCount(source.getAttemptCount())
                .maxAttempts(source.getMaxAttempts())
                .createdAt(source.getCreatedAt())
                .updatedAt(source.getUpdatedAt())
                .build();
    }
}
