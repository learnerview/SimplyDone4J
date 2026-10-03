package io.github.learnerview.simplydone4j.event;

import io.github.learnerview.simplydone4j.entity.JobEntity;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable payload carried by every {@link JobEvent}.
 *
 * <p>Frozen at construction because events are published to listeners and may be
 * queued, retried, or handed to an async publisher. A mutable object handed to
 * several listeners could be changed by the first one to run, leaving the rest
 * reading a different job state than the event described.</p>
 */
@Getter
@Builder
public class JobEventData {
    private final String jobId;
    private final String jobType;
    private final String producer;
    private final String status;
    private final String priority;
    private final String result;
    private final Integer attempt;
    private final Integer maxAttempts;
    private final Long durationMs;
    private final Instant timestamp;
    private final Map<String, Object> additionalData;

    /**
     * Snapshots a job's current state.
     *
     * <p>Copies rather than aliasing: {@code job} is still owned and mutable by the
     * engine, and a subscriber that read the same instance later would see the
     * post-transition state under a pre-transition timestamp.</p>
     */
    public static JobEventData from(JobEntity job) {
        return builder()
                .jobId(job.getId())
                .jobType(job.getJobType())
                .producer(job.getProducer())
                .status(job.getStatus().name())
                .priority(job.getPriority().name())
                .result(job.getResult())
                .timestamp(Instant.now())
                .build();
    }
}
