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
import io.github.learnerview.simplydone4j.service.WorkerCapacity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Weighted deficit round-robin scheduler.
 *
 * <p>The weights describe a share of <em>jobs</em>, not a share of polls. For every job
 * slot the scheduler adds each queue's weight to its deficit counter and serves the
 * backlogged queue with the largest deficit. Deficit is <em>not</em> reset when a queue
 * drains: an idle queue keeps the credit it banked, so a burst can be served immediately
 * instead of waiting a full round. Credit is capped at one full round, which prevents a
 * queue that has been idle for a long time from starving the others when it wakes up.</p>
 *
 * <p>Each tick claims up to a batch rather than a single job. Serving one job per tick
 * caps throughput at {@code 1000 / pollingIntervalMs} jobs per second per instance
 * regardless of worker pool size, which is the dominant limit on this design.</p>
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
    private final WorkerCapacity capacity;
    private final long idlePollingIntervalMs;
    private final long busyPollingIntervalMs;

    /**
     * Whether the last poll found work. Drives how quickly the loop comes back: while
     * there is work to hand out, a worker that finishes should get the next job at once.
     */
    private volatile boolean workPending;

    public SchedulerEngine(QueueRepository queueRepo, JobRepository jobRepo,
                            JobExecutorService executor, FencingTokenSequence fencingTokens,
                            SimplyDoneProperties config) {
        this(queueRepo, jobRepo, executor, fencingTokens, config, JobMetrics.NOOP);
    }

    public SchedulerEngine(QueueRepository queueRepo, JobRepository jobRepo,
                            JobExecutorService executor, FencingTokenSequence fencingTokens,
                            SimplyDoneProperties config, JobMetrics metrics) {
        this(queueRepo, jobRepo, executor, fencingTokens, config, metrics, null);
    }

    /**
     * @param capacity how much work the workers can take on. When {@code null} the
     *                 scheduler claims the configured batch size and nothing else changes.
     */
    public SchedulerEngine(QueueRepository queueRepo, JobRepository jobRepo,
                            JobExecutorService executor, FencingTokenSequence fencingTokens,
                            SimplyDoneProperties config, JobMetrics metrics, WorkerCapacity capacity) {
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
        this.capacity = capacity;
        // A batch size of zero means "as many as the workers can start", which is what
        // keeps the pipeline from idling while work is queued.
        this.batchSize = config.getScheduler().getBatchSize() > 0
                ? config.getScheduler().getBatchSize()
                : (capacity == null ? 10 : Math.max(1, capacity.maxConcurrency()));
        this.leaseTimeoutSeconds = config.getWorker().getLeaseTimeoutSeconds();
        this.idlePollingIntervalMs = Math.max(1L, config.getScheduler().getPollingIntervalMs());
        this.busyPollingIntervalMs = Math.max(1L, config.getScheduler().getBusyPollingIntervalMs());
        this.workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Short while there is work to hand out, long when there is not. The loop uses
     * this to decide whether to come back immediately or to wait for something to
     * happen.</p>
     */
    @Override
    public Duration nextPollDelay() {
        return Duration.ofMillis(workPending ? busyPollingIntervalMs : idlePollingIntervalMs);
    }

    /**
     * Synchronized because {@code deficit} is shared mutable state. Spring's default
     * scheduler pool has one thread, but {@code spring.task.scheduling.pool.size} is a
     * normal thing to raise, and an unsynchronized counter would then corrupt weights
     * non-deterministically under concurrency.
     *
     * <p>Scheduled by the library's own timer rather than by {@code @Scheduled}, so that
     * the library never has to enable scheduling in the host application.</p>
     */
    @Override
    public synchronized void poll() {
        long startedAt = System.nanoTime();
        int claimed = 0;
        try {
            int limit = claimLimit();
            // Work on a copy: the scheduler decrements the depths as it allocates slots,
            // and a repository is allowed to hand back a shared or cached map.
            Map<JobPriority, Long> depths = new EnumMap<>(JobPriority.class);
            depths.putAll(queueRepo.queueDepths());
            boolean backlogged = depths.values().stream().anyMatch(depth -> depth > 0);
            if (!backlogged) {
                workPending = false;
                return;
            }
            if (limit <= 0) {
                workPending = true;
                return;
            }

            // Choose a priority for each job slot, one slot at a time. The deficit is
            // updated per job, not per poll: charging a whole batch to one queue made the
            // weights describe poll share rather than job share, so a queue whose batch
            // was smaller than the limit paid the same price for less work.
            int[] quotas = new int[PRIORITIES.length];
            for (int slot = 0; slot < limit; slot++) {
                for (int i = 0; i < PRIORITIES.length; i++) {
                    deficit[i] = Math.min(totalWeight, deficit[i] + weights[i]);
                }
                int bestIdx = selectBackloggedQueue(depths);
                if (bestIdx < 0) {
                    break;
                }
                quotas[bestIdx]++;
                depths.merge(PRIORITIES[bestIdx], -1L, Long::sum);
                deficit[bestIdx] -= totalWeight;
            }

            for (int i = 0; i < PRIORITIES.length; i++) {
                if (quotas[i] <= 0) {
                    continue;
                }
                List<String> batch = queueRepo.claimReady(PRIORITIES[i], quotas[i]);
                if (batch.size() < quotas[i]) {
                    // The queue drained between the depth read and the claim. Give the
                    // unserved slots back so the next poll does not punish this queue for
                    // work it could not supply.
                    deficit[i] += (quotas[i] - batch.size()) * totalWeight;
                }
                for (String jobId : batch) {
                    executeClaimedJobOrRestore(jobId, PRIORITIES[i]);
                    claimed++;
                }
            }
            workPending = true;

        } catch (Exception e) {
            log.error("Scheduler poll failed", e);
        } finally {
            metrics.recordPoll((System.nanoTime() - startedAt) / 1_000_000L, claimed);
            metrics.recordClaimed(claimed);
        }
    }

    /**
     * How many jobs to claim this poll: the configured batch size, capped by what the
     * workers can start right now.
     */
    private int claimLimit() {
        if (capacity == null) return batchSize;
        return Math.min(batchSize, capacity.availableSlots());
    }

    private int selectBackloggedQueue(Map<JobPriority, Long> depths) {
        int bestIdx = -1;
        int bestDeficit = Integer.MIN_VALUE;
        for (int i = 0; i < PRIORITIES.length; i++) {
            if (depths.getOrDefault(PRIORITIES[i], 0L) <= 0) continue;
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
            executeClaimedJob(jobId, priority);
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

    private void executeClaimedJob(String jobId, JobPriority priority) {
        Instant now = Instant.now();
        String leaseToken = fencingTokens.nextToken();
        Instant visibleUntil = now.plusSeconds(leaseTimeoutSeconds);

        int updated = jobRepo.claimForExecution(jobId, leaseToken, workerId, visibleUntil, now,
                JobStatus.QUEUED, JobStatus.RUNNING);
        if (updated != 1) {
            restoreIfStillQueued(jobId, priority);
            return;
        }

        jobRepo.findById(jobId).ifPresentOrElse(
                executor::execute,
                () -> log.warn("Claimed job {} not found in DB", jobId)
        );
    }

    /**
     * A claim can lose to a concurrent writer after {@code claimReady} already removed the
     * queue entry. If the job is still {@code QUEUED}, put it back so the next poll can
     * try again. If it is terminal or gone, do nothing: re-queueing a missing job would
     * make the next poll remove it, fail the claim, and enqueue it again forever.
     */
    private void restoreIfStillQueued(String jobId, JobPriority priority) {
        try {
            jobRepo.findById(jobId)
                    .filter(job -> job.getStatus() == JobStatus.QUEUED)
                    .ifPresent(job -> queueRepo.enqueue(
                            jobId, priority, System.currentTimeMillis()));
        } catch (RuntimeException e) {
            log.warn("Could not check or restore job {} after a lost claim", jobId, e);
        }
    }
}
