package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.metrics.JobMetrics;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.FencingTokenSequence;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.github.learnerview.simplydone4j.service.JobExecutorService;
import io.github.learnerview.simplydone4j.service.SchedulerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Weighted deficit round-robin scheduler.
 *
 * <p>Each poll adds every queue's weight to its deficit counter and serves the backlogged
 * queue with the largest deficit. Deficit is <em>not</em> reset when a queue drains: that
 * is the property that makes DRR different from a plain weighted round-robin. An idle queue
 * accumulates credit, so when a burst arrives it can transmit immediately instead of
 * waiting a full round to re-establish its share. Resetting the counter on idle (or on a
 * lost claim) would silently degrade the algorithm to weighted RR.</p>
 *
 * <p>Each tick claims a batch rather than a single job. Serving one job per tick caps
 * throughput at {@code 1000 / pollingIntervalMs} jobs per second per instance regardless
 * of worker pool size, which is the dominant limit on this design.</p>
 */
public final class SchedulerEngine implements SchedulerService {
    private static final Logger log = LoggerFactory.getLogger(SchedulerEngine.class);
    private static final JobPriority[] PRIORITIES = JobPriority.values();

    private final QueueRepository queueRepo;
    private final JobRepository jobRepo;
    private final JobExecutorService executor;
    private final FencingTokenSequence fencingTokens;
    private final JobMetrics metrics;
    private final int[] weights;
    private final int[] deficit;
    private final int totalWeight;
    private final int batchSize;
    private final int leaseTimeoutSeconds;
    private final String workerId;

    public SchedulerEngine(QueueRepository queueRepo, JobRepository jobRepo,
                            JobExecutorService executor, FencingTokenSequence fencingTokens,
                            SimplyDoneProperties config) {
        this(queueRepo, jobRepo, executor, fencingTokens, config, JobMetrics.NOOP);
    }

    public SchedulerEngine(QueueRepository queueRepo, JobRepository jobRepo,
                            JobExecutorService executor, FencingTokenSequence fencingTokens,
                            SimplyDoneProperties config, JobMetrics metrics) {
        this.queueRepo = queueRepo;
        this.jobRepo = jobRepo;
        this.executor = executor;
        this.fencingTokens = fencingTokens;
        this.metrics = metrics == null ? JobMetrics.NOOP : metrics;
        this.weights = new int[]{
                config.getScheduler().getWeights().getHigh(),
                config.getScheduler().getWeights().getNormal(),
                config.getScheduler().getWeights().getLow()
        };
        this.deficit = new int[PRIORITIES.length];
        this.totalWeight = weights[0] + weights[1] + weights[2];
        this.batchSize = Math.max(1, config.getScheduler().getBatchSize());
        this.leaseTimeoutSeconds = config.getWorker().getLeaseTimeoutSeconds();
        this.workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Synchronized because {@code deficit} is shared mutable state. Spring's default
     * scheduler pool has one thread, but {@code spring.task.scheduling.pool.size} is a
     * normal thing to raise, and an unsynchronized counter would then corrupt weights
     * non-deterministically under concurrency.
     */
    @Scheduled(
            initialDelay = 2000,
            fixedDelayString = "${simplydone4j.scheduler.polling-interval-ms:1000}"
    )
    @Override
    public synchronized void poll() {
        long startedAt = System.nanoTime();
        int claimed = 0;
        try {
            for (int i = 0; i < PRIORITIES.length; i++) {
                deficit[i] += weights[i];
            }

            int bestIdx = selectBackloggedQueue();
            if (bestIdx < 0) return;

            List<String> batch = queueRepo.claimReady(PRIORITIES[bestIdx], batchSize);
            if (batch.isEmpty()) return;

            deficit[bestIdx] -= totalWeight;
            for (String jobId : batch) {
                executeClaimedJobOrRestore(jobId, PRIORITIES[bestIdx]);
                claimed++;
            }

        } catch (Exception e) {
            log.error("Scheduler poll failed", e);
        } finally {
            metrics.recordPoll((System.nanoTime() - startedAt) / 1_000_000L, claimed);
            metrics.recordClaimed(claimed);
        }
    }

    private int selectBackloggedQueue() {
        int bestIdx = -1;
        int bestDeficit = Integer.MIN_VALUE;
        for (int i = 0; i < PRIORITIES.length; i++) {
            if (queueRepo.queueSize(PRIORITIES[i]) <= 0) continue;
            if (deficit[i] > bestDeficit) {
                bestDeficit = deficit[i];
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    /**
     * Dispatches one claimed job, putting it back on the queue if anything goes wrong.
     *
     * <p>{@link QueueRepository#claimReady} has already removed the id from the priority
     * ZSET by the time this runs, and the ZSET is the <em>only</em> thing that hands work to
     * the engine -- nothing scans {@code QUEUED} status, because a queued job with no queue
     * entry cannot be distinguished from one that is merely not due yet. So a job that
     * throws here and is not restored is gone permanently: its hash still reads
     * {@code QUEUED}, {@code getJob} reports healthy queued work, and nothing ever runs it.
     *
     * <p>Restoring at the current time re-arms it for the very next poll. If the failure
     * happened <em>after</em> {@code claimForExecution} committed, the job is RUNNING with a
     * live lease and this restore is a harmless no-op: the next poll's
     * {@code claimForExecution} sees a status that is not QUEUED, declines it and drops the
     * queue entry again, leaving the lease reaper in charge.</p>
     */
    private void executeClaimedJobOrRestore(String jobId, JobPriority priority) {
        try {
            executeClaimedJob(jobId);
        } catch (Exception e) {
            log.error("Failed to dispatch claimed job {}; restoring it to the {} queue so it is "
                    + "not lost", jobId, priority, e);
            try {
                queueRepo.enqueue(jobId, priority, System.currentTimeMillis());
            } catch (RuntimeException restoreFailure) {
                // Nothing left to try: the engine has no durable record of this job outside
                // the queue, so the operator has to be told explicitly.
                log.error("Could not restore job {} to the {} queue. It is stranded with no "
                        + "recovery path and must be re-submitted.", jobId, priority, restoreFailure);
            }
        }
    }

    private void executeClaimedJob(String jobId) {
        Instant now = Instant.now();
        String leaseToken = fencingTokens.nextToken();
        Instant visibleUntil = now.plusSeconds(leaseTimeoutSeconds);

        int updated = jobRepo.claimForExecution(jobId, leaseToken, workerId, visibleUntil, now,
                JobStatus.QUEUED, JobStatus.RUNNING);
        if (updated != 1) return;

        jobRepo.findById(jobId).ifPresentOrElse(
                executor::execute,
                () -> log.warn("Claimed job {} not found in DB", jobId)
        );
    }
}
