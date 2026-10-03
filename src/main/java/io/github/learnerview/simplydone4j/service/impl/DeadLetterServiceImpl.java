package io.github.learnerview.simplydone4j.service.impl;

import lombok.extern.slf4j.Slf4j;
import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.event.JobEvent;
import io.github.learnerview.simplydone4j.event.JobEventData;
import io.github.learnerview.simplydone4j.event.JobEventPublisher;
import io.github.learnerview.simplydone4j.exception.JobNotFoundException;
import io.github.learnerview.simplydone4j.metrics.JobMetrics;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.github.learnerview.simplydone4j.service.DeadLetterService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Default {@link DeadLetterService}.
 *
 * <p>A requeue deliberately grants a fresh attempt budget rather than topping up the
 * remaining one. The job only reached the DLQ because the thing it depends on was
 * unavailable; restoring the original budget would send it straight back to the DLQ on
 * the next failure, which defeats the point of an operator overriding the outcome.</p>
 *
 * <p>The DLQ is not a lease-bearing state, so there is no fencing token to compare and
 * the requeue is a plain write. Two operators requeueing the same job at the same moment
 * is harmless: the enqueue is a {@code ZADD} on the same member, so the second write is
 * idempotent rather than duplicating the job on the queue.</p>
 */
@Slf4j
public final class DeadLetterServiceImpl implements DeadLetterService {


    private final JobRepository jobRepo;
    private final QueueRepository queueRepo;
    private final JobEventPublisher eventPublisher;
    private final SimplyDoneProperties config;
    private final JobMetrics metrics;

    public DeadLetterServiceImpl(JobRepository jobRepo, QueueRepository queueRepo,
                                 JobEventPublisher eventPublisher, SimplyDoneProperties config) {
        this(jobRepo, queueRepo, eventPublisher, config, JobMetrics.NOOP);
    }

    public DeadLetterServiceImpl(JobRepository jobRepo, QueueRepository queueRepo,
                                 JobEventPublisher eventPublisher, SimplyDoneProperties config,
                                 JobMetrics metrics) {
        this.jobRepo = jobRepo;
        this.queueRepo = queueRepo;
        this.eventPublisher = eventPublisher;
        this.config = config;
        this.metrics = metrics == null ? JobMetrics.NOOP : metrics;
    }

    @Override
    public List<JobEntity> listDeadLettered(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, got " + limit);
        }
        return jobRepo.findByStatus(JobStatus.DLQ).stream()
                // Newest first. The null handling has to sit *inside* the reversed
                // comparator; reversing the whole thing would also flip the nulls to
                // the front, which is exactly where a job you cannot date should not be.
                .sorted(Comparator.comparing(JobEntity::getUpdatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit)
                .toList();
    }

    @Override
    public JobEntity requeue(String jobId) {
        JobEntity job = jobRepo.findById(jobId)
                .orElseThrow(() -> new JobNotFoundException(jobId));

        if (job.getStatus() != JobStatus.DLQ) {
            throw new IllegalStateException(
                    "Only dead-lettered jobs can be requeued; job " + jobId + " is " + job.getStatus());
        }

        int budget = job.getMaxAttempts() > 0 ? job.getMaxAttempts() : config.getRetry().getMaxAttempts();
        if (budget <= 0) {
            throw new IllegalStateException("Job " + jobId
                    + " has no attempt budget; set simplydone4j.retry.max-attempts before requeueing");
        }

        Instant now = Instant.now();
        job.setStatus(JobStatus.QUEUED);
        job.setAttemptCount(0);
        job.setMaxAttempts(budget);
        job.setNextRunAt(now);
        job.setUpdatedAt(now);

        // Clear the terminal fields so the job is not simultaneously marked queued and
        // completed; downstream reporting counts on these being mutually exclusive.
        // startedAt belongs here too: it is written only when the executor claims a
        // QUEUED job, so leaving the previous run's value behind would show a queued
        // job as already started until it is claimed again.
        job.setStartedAt(null);
        job.setCompletedAt(null);
        job.setVisibleAt(null);
        job.setLeaseOwner(null);
        job.setLeaseToken(null);
        job.setResult(null);

        jobRepo.save(job);
        queueRepo.enqueue(job.getId(), job.getPriority(), now.toEpochMilli());

        metrics.recordSubmitted(job.getPriority());
        log.info("Requeued dead-lettered job {} (type={}, budget={})", job.getId(), job.getJobType(), budget);
        eventPublisher.publish(JobEvent.JOB_CREATED, JobEventData.builder()
                .jobId(job.getId())
                .jobType(job.getJobType())
                .producer(job.getProducer())
                .status(JobStatus.QUEUED.name())
                .attempt(0)
                .maxAttempts(budget)
                .timestamp(now)
                .build());

        return job;
    }

    @Override
    public List<JobEntity> requeueAll(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, got " + limit);
        }

        List<JobEntity> requeued = new ArrayList<>();
        for (JobEntity candidate : listDeadLettered(limit)) {
            try {
                requeued.add(requeue(candidate.getId()));
            } catch (RuntimeException e) {
                // One unrecoverable job must not abort the sweep; the rest still get
                // their chance and the operator sees exactly what moved.
                log.warn("Could not requeue dead-lettered job {}: {}", candidate.getId(), e.getMessage());
            }
        }
        return requeued;
    }
}

