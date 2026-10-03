package io.github.learnerview.simplydone4j.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Append-only record of one attempt at a job.
 *
 * <p>Distinct from {@link JobEntity} on purpose. A job row holds current state and is
 * overwritten on every transition; this holds what happened and is never modified, which
 * is what makes per-attempt debugging possible after the job itself has moved on.</p>
 *
 * <p>Needs a no-arg constructor and setters because Jackson deserializes it from the
 * stored JSON log.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobExecutionLog {
    private String id;
    private String jobId;
    private int attempt;
    private String status;
    private String message;
    private Long durationMs;
    private Instant executedAt;
}
