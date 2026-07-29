package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.github.learnerview.simplydone4j.service.JobExecutorService;
import io.github.learnerview.simplydone4j.service.SchedulerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public final class SchedulerEngine implements SchedulerService {
    private static final Logger log = LoggerFactory.getLogger(SchedulerEngine.class);
    private static final JobPriority[] PRIORITIES = JobPriority.values();

    private final QueueRepository queueRepo;
    private final JobRepository jobRepo;
    private final JobExecutorService executor;
    private final int[] weights;
    private final int[] deficit;
    private final int totalWeight;
    private final int leaseTimeoutSeconds;
    private final String workerId;

    public SchedulerEngine(QueueRepository queueRepo, JobRepository jobRepo,
                            JobExecutorService executor, SimplyDoneProperties config) {
        this.queueRepo = queueRepo;
        this.jobRepo = jobRepo;
        this.executor = executor;
        this.weights = new int[]{
                config.getScheduler().getWeights().getHigh(),
                config.getScheduler().getWeights().getNormal(),
                config.getScheduler().getWeights().getLow()
        };
        this.deficit = new int[PRIORITIES.length];
        this.totalWeight = weights[0] + weights[1] + weights[2];
        this.leaseTimeoutSeconds = config.getWorker().getLeaseTimeoutSeconds();
        this.workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Scheduled(fixedDelayString = "${simplydone4j.scheduler.polling-interval-ms:1000}")
    @Override
    public void poll() {
        for (int i = 0; i < PRIORITIES.length; i++) {
            deficit[i] += weights[i];
        }

        int bestIdx = -1;
        int bestDeficit = Integer.MIN_VALUE;
        for (int i = 0; i < PRIORITIES.length; i++) {
            if (deficit[i] > bestDeficit && queueRepo.queueSize(PRIORITIES[i]) > 0) {
                bestDeficit = deficit[i];
                bestIdx = i;
            }
        }

        if (bestIdx == -1) return;

        Optional<String> claimed = queueRepo.claimNextReady(PRIORITIES[bestIdx]);
        if (claimed.isEmpty()) return;

        deficit[bestIdx] -= totalWeight;
        executeClaimedJob(claimed.get());
    }

    private void executeClaimedJob(String jobId) {
        Instant now = Instant.now();
        String leaseToken = UUID.randomUUID().toString();
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
