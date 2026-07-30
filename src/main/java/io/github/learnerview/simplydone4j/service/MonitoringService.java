package io.github.learnerview.simplydone4j.service;

import io.github.learnerview.simplydone4j.dto.QueueStatsResponse;

import java.util.Map;

/**
 * Provides queue statistics and per-status job counts.
 * <p>
 * Auto-configured by SimplyDone4J when {@code simplydone4j.monitoring.enabled=true}
 * (the default). Override by registering your own {@code MonitoringService} bean.
 */
public interface MonitoringService {

    /**
     * Returns a snapshot of queue depths, running counts, success/failure totals
     * and derived rates.
     */
    QueueStatsResponse getStats();

    /**
     * Returns a map of every {@link io.github.learnerview.simplydone4j.model.JobStatus}
     * name → count of jobs currently in that state.
     */
    Map<String, Long> getCountByStatus();

    /**
     * Returns a map of priority-queue name → current depth (number of jobs waiting).
     */
    Map<String, Long> getQueueDepths();
}
