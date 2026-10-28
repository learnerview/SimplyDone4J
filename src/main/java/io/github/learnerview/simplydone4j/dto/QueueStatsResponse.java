package io.github.learnerview.simplydone4j.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * Aggregated queue and outcome counts, computed on demand.
 *
 * <p>A point-in-time reading rather than a live view: the individual counts come from
 * separate Redis structures and cannot be sampled atomically, so {@code successRate} is
 * derived from a set of figures that were each true at a slightly different instant.
 * Precise enough for a dashboard or an alert threshold, not for reconciliation.</p>
 */
@Getter
@Builder
public class QueueStatsResponse {
    private final long highQueueSize;
    private final long normalQueueSize;
    private final long lowQueueSize;
    private final long totalQueued;
    private final long totalRunning;
    private final long totalSuccess;
    private final long totalFailed;
    private final long totalDlq;
    private final long totalProcessed;
    private final double successRate;
    private final double inFlightRetryRate;
}
