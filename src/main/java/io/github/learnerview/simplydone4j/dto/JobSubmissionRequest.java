package io.github.learnerview.simplydone4j.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

/**
 * Inbound request body for job submission.
 *
 * <p>Mutable with a no-arg constructor because Jackson binds it. The bean-validation
 * annotations are what reject bad input: {@code jobType} and {@code idempotencyKey} are
 * required because a job with neither can never be de-duplicated or routed, and the
 * numeric bounds stop a caller from submitting a job with a zero-length retry budget or
 * a timeout that would trip the watchdog the instant the handler starts.</p>
 */
@Data
public class JobSubmissionRequest {
    @NotBlank
    private String jobType;

    @NotBlank
    private String idempotencyKey;

    /**
     * Optional mutual-exclusion key. When uniqueness enforcement is enabled, two jobs
     * sharing this key never run at the same time; the loser is deferred, not failed.
     */
    @Size(max = 200)
    private String uniqueKey;

    private String priority;
    private Map<String, Object> payload;
    private Instant nextRunAt;

    @Min(1)
    private Integer maxAttempts;

    @Min(1)
    private Integer timeoutSeconds;

    private String callbackUrl;
}
