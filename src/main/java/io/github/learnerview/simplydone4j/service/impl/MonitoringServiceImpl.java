package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.dto.QueueStatsResponse;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobQueryRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.github.learnerview.simplydone4j.service.MonitoringService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Default implementation of {@link MonitoringService}.
 * Auto-configured when {@code simplydone4j.monitoring.enabled=true}.
 */
public final class MonitoringServiceImpl implements MonitoringService {

    private final JobQueryRepository jobRepo;
    private final QueueRepository queueRepo;
    
    public MonitoringServiceImpl(JobQueryRepository jobRepo, QueueRepository queueRepo) {
        this.jobRepo = jobRepo;
        this.queueRepo = queueRepo;
    }

    @Override
    public QueueStatsResponse getStats() {
        // One read of every queue's depth, not one per priority.
        Map<JobPriority, Long> depths = queueRepo.queueDepths();
        long highQ = depths.getOrDefault(JobPriority.HIGH, 0L);
        long normalQ = depths.getOrDefault(JobPriority.NORMAL, 0L);
        long lowQ = depths.getOrDefault(JobPriority.LOW, 0L);
        long totalQueued = highQ + normalQ + lowQ;
        long totalRunning = jobRepo.countByStatus(JobStatus.RUNNING);
        long totalSuccess = jobRepo.countByStatus(JobStatus.SUCCESS);
        long totalFailed = jobRepo.countByStatus(JobStatus.FAILED);
        long totalDlq = jobRepo.countByStatus(JobStatus.DLQ);
        long totalProcessed = totalSuccess + totalDlq + totalFailed;

        double successRate = totalProcessed > 0
                ? (double) totalSuccess / totalProcessed * 100.0 : 0.0;
        double inFlightRetryRate = totalProcessed > 0
                ? (double) jobRepo.countByStatus(JobStatus.RETRY_SCHEDULED) / totalProcessed * 100.0 : 0.0;

        return QueueStatsResponse.builder()
                .highQueueSize(highQ)
                .normalQueueSize(normalQ)
                .lowQueueSize(lowQ)
                .totalQueued(totalQueued)
                .totalRunning(totalRunning)
                .totalSuccess(totalSuccess)
                .totalFailed(totalFailed)
                .totalDlq(totalDlq)
                .totalProcessed(totalProcessed)
                .successRate(successRate)
                .inFlightRetryRate(inFlightRetryRate)
                .build();
    }

    @Override
    public Map<String, Long> getCountByStatus() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (JobStatus status : JobStatus.values()) {
            counts.put(status.name(), jobRepo.countByStatus(status));
        }
        return counts;
    }

    @Override
    public Map<String, Long> getQueueDepths() {
        Map<String, Long> depths = new TreeMap<>();
        queueRepo.queueDepths().forEach((priority, size) -> depths.put(priority.name(), size));
        return depths;
    }
}
