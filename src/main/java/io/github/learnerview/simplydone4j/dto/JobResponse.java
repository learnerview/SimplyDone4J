package io.github.learnerview.simplydone4j.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.Map;

/**
 * Read-only view of a job returned by the API.
 *
 * <p>Immutable and built from a snapshot. Callers get a consistent picture of a job that
 * may be transitioning underneath them, rather than a live object whose fields shift
 * between two field reads.</p>
 *
 * <p>Note this DTO intentionally omits {@code leaseToken}: the fencing token is internal
 * engine state, and exposing it would invite callers to reason about lease ownership that
 * is not theirs to manage.</p>
 */
@Getter
@Builder
public class JobResponse {
    private final String id;
    private final String jobType;
    private final String producer;
    private final String idempotencyKey;
    private final String status;
    private final String priority;
    private final Map<String, Object> payload;
    private final String result;
    private final Instant nextRunAt;
    private final Instant visibleAt;
    private final String leaseOwner;
    private final Integer timeoutSeconds;
    private final String callbackUrl;
    private final Instant startedAt;
    private final Instant completedAt;
    private final int attemptCount;
    private final int maxAttempts;
    private final Instant createdAt;
    private final Instant updatedAt;
}
