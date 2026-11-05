package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.github.learnerview.simplydone4j.service.JobExecutorService;
import io.github.learnerview.simplydone4j.service.JobSignal;
import io.github.learnerview.simplydone4j.service.SchedulerService;
import io.github.learnerview.simplydone4j.service.WorkerCapacity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class SchedulerEngine implements SchedulerService {
    private static final Logger log = LoggerFactory.getLogger(SchedulerEngine.class);
    private static final JobPriority[] PRIORITIES = JobPriority.values();

    private final QueueRepository queueRepo;
    private final JobRepository jobRepo;
    private final JobExecutorService executor;
    private final WorkerCapacity capacity;
    private final JobSignal signal;
    private final long[] weights;
    private final long[] deficit;
    private final long totalWeight;
    private final int leaseTimeoutSeconds;
    private final int maxClaimBatch;
    private final String workerId;

    public SchedulerEngine(QueueRepository queueRepo, JobRepository jobRepo,
                            JobExecutorService executor, SimplyDoneProperties config) {
        this(queueRepo, jobRepo, executor, new WorkerCapacity(config.getExecutor().getMaxPoolSize()),
                JobSignal.NOOP, config);
    }

    public SchedulerEngine(QueueRepository queueRepo, JobRepository jobRepo,
                            JobExecutorService executor, WorkerCapacity capacity,
                            JobSignal signal, SimplyDoneProperties config) {
        this.queueRepo = queueRepo;
        this.jobRepo = jobRepo;
        this.executor = executor;
        this.capacity = capacity;
        this.signal = signal;
        long high = Math.max(0, config.getScheduler().getWeights().getHigh());
        long normal = Math.max(0, config.getScheduler().getWeights().getNormal());
        long low = Math.max(0, config.getScheduler().getWeights().getLow());
        if (high + normal + low == 0) {
            high = normal = low = 1;
        }
        this.weights = new long[]{high, normal, low};
        this.deficit = new long[PRIORITIES.length];
        this.totalWeight = weights[0] + weights[1] + weights[2];
        this.leaseTimeoutSeconds = Math.max(1, config.getWorker().getLeaseTimeoutSeconds());
        this.maxClaimBatch = Math.max(1, config.getScheduler().getMaxClaimBatch());
        this.workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Override
    public void poll() {
        pollOnce();
    }

    /**
     * Claims up to the idle worker permits in one Redis round trip.
     *
     * <p>The old loop removed one job per fixed tick. With a 1s tick that capped an
     * instance at one job per second regardless of executor size, and the extra idle
     * ticks were pure Redis load. Batching makes the claim rate follow available
     * capacity, and the deficit counters still decide which priority gets the next
     * batch.</p>
     */
    @Override
    public synchronized int pollOnce() {
        int available = capacity.availablePermits();
        if (available <= 0) return 0;

        try {
            for (int i = 0; i < PRIORITIES.length; i++) {
                deficit[i] += weights[i];
            }

            // Select the best-deficit priority that actually has work due now.
            // queueSize() counts every member of the priority ZSET, including not-yet-due
            // delayed jobs, so a HIGH queue holding only a far-future job must not block
            // the NORMAL/LOW queues behind it. When claimReady() returns nothing due,
            // exclude that priority for the rest of this poll and pick again.
            boolean[] excluded = new boolean[PRIORITIES.length];
            int selectedIdx = -1;
            JobPriority priority = null;
            List<String> claimed = List.of();
            for (;;) {
                int bestIdx = -1;
                long bestDeficit = Long.MIN_VALUE;

                for (int i = 0; i < PRIORITIES.length; i++) {
                    if (excluded[i]) {
                        continue;
                    }
                    if (queueRepo.queueSize(PRIORITIES[i]) > 0) {
                        if (deficit[i] > bestDeficit) {
                            bestDeficit = deficit[i];
                            bestIdx = i;
                        }
                    } else {
                        deficit[i] = 0;
                    }
                }

                if (bestIdx == -1) return 0;

                JobPriority candidate = PRIORITIES[bestIdx];
                List<String> candidateClaim = queueRepo.claimReady(
                        candidate, Math.min(available, maxClaimBatch));
                if (candidateClaim.isEmpty()) {
                    deficit[bestIdx] = 0;
                    excluded[bestIdx] = true;
                    continue;
                }

                selectedIdx = bestIdx;
                priority = candidate;
                claimed = candidateClaim;
                break;
            }

            int dispatched = 0;
            for (String jobId : claimed) {
                if (!capacity.tryAcquire()) {
                    restore(priority, jobId);
                    continue;
                }

                // executeClaimedJob() hands the permit to the executor on success; any other
                // outcome -- a false return or an exception from Redis between the claim and
                // the dispatch -- must give the permit back or the pool drains to zero.
                boolean handedOff = false;
                try {
                    handedOff = executeClaimedJob(jobId, priority);
                } catch (RuntimeException e) {
                    log.error("Failed to dispatch claimed job {}; attempting to return it to the queue", jobId, e);
                    restore(priority, jobId);
                }
                if (handedOff) {
                    dispatched++;
                    deficit[selectedIdx] -= totalWeight;
                } else {
                    capacity.release();
                }
            }

            if (dispatched > 0) {
                signal.signal();
            }
            return dispatched;
        } catch (Exception e) {
            if (isInterrupt(e)) {
                Thread.currentThread().interrupt();
                log.debug("Scheduler poll interrupted during shutdown", e);
                return 0;
            }
            log.error("Scheduler poll failed", e);
            return 0;
        }
    }

    private static boolean isInterrupt(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof InterruptedException) {
                return true;
            }
        }
        return false;
    }

    private boolean executeClaimedJob(String jobId, JobPriority priority) {
        Instant now = Instant.now();
        String leaseToken = UUID.randomUUID().toString();
        Instant visibleUntil = now.plusSeconds(leaseTimeoutSeconds);

        int updated = jobRepo.claimForExecution(jobId, leaseToken, workerId, visibleUntil, now,
                JobStatus.QUEUED, JobStatus.RUNNING);
        if (updated != 1) {
            restore(priority, jobId);
            return false;
        }

        Optional<JobEntity> claimed = jobRepo.findById(jobId);
        if (claimed.isEmpty()) {
            log.warn("Claimed job {} not found in DB", jobId);
            return false;
        }

        JobEntity job = claimed.get();
        try {
            executor.execute(job);
            return true;
        } catch (RuntimeException e) {
            log.error("Could not dispatch job {}; returning it to the queue", jobId, e);
            job.setStatus(JobStatus.QUEUED);
            job.setLeaseOwner(null);
            job.setLeaseToken(null);
            job.setVisibleAt(null);
            job.setUpdatedAt(Instant.now());
            boolean restored = jobRepo.saveIfLeaseHeld(job, leaseToken);
            if (!restored) {
                log.warn("Could not return job {} to QUEUED after a dispatch failure; "
                        + "the lease was lost or the record changed", jobId);
                return false;
            }
            queueRepo.enqueue(jobId, priority, Instant.now().toEpochMilli());
            return false;
        }
    }

    /**
     * Puts an id back only when the stored job is still queued. Re-enqueuing a job that a
     * competing worker has already claimed would create a duplicate queue entry.
     */
    private void restore(JobPriority priority, String jobId) {
        try {
            long now = Instant.now().toEpochMilli();
            jobRepo.findById(jobId)
                    .filter(job -> job.getStatus() == JobStatus.QUEUED)
                    .ifPresent(job -> queueRepo.enqueue(jobId, priority,
                            job.getNextRunAt() != null
                                    ? Math.max(0L, job.getNextRunAt().toEpochMilli())
                                    : now));
        } catch (RuntimeException e) {
            log.warn("Could not restore claimed job {} to the queue", jobId, e);
        }
    }
}
