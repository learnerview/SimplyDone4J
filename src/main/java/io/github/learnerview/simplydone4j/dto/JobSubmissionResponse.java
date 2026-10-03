package io.github.learnerview.simplydone4j.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/**
 * Acknowledgement returned when a submission is accepted.
 *
 * <p>Also returned when an idempotency key resolves to a job that already exists. The
 * {@code status} field is what tells the two cases apart, so a client retrying after a
 * timeout can tell "I created this" from "this was already there" without a second
 * round trip.</p>
 */
@Getter
@Builder
public class JobSubmissionResponse {
    private final String jobId;
    private final String status;
    private final String jobType;
    private final String priority;
    private final Instant scheduledAt;
}
