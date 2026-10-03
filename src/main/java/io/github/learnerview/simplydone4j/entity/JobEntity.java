package io.github.learnerview.simplydone4j.entity;

import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.Instant;

/**
 * The persisted job record.
 *
 * <p>Kept mutable on purpose. The engine transitions a job by loading it, mutating the
 * fields, and writing it back under a fencing check, so an immutable representation would
 * mean rebuilding the whole object per transition for no benefit.</p>
 *
 * <p>The no-arg constructor and setters are load-bearing, not incidental: Jackson maps this
 * class to and from the Redis hash in both directions, and a constructor-less builder would
 * break deserialization of jobs written by an earlier version.</p>
 *
 * <p>{@code leaseToken} is the fencing token. It is monotonic per job and is the only thing
 * that authorises a terminal write, which is why {@code RetryService} compares against the
 * token it was issued rather than whatever the current read returns.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobEntity {
    private String id;
    private String jobType;
    private String producer;
    private String idempotencyKey;
    private String uniqueKey;
    private JobStatus status;
    private JobPriority priority;
    @ToString.Exclude
    private String payload;
    private String result;
    private Instant nextRunAt;
    private Instant visibleAt;
    private String leaseOwner;
    private String leaseToken;
    private Integer timeoutSeconds;
    private String callbackUrl;
    private Instant startedAt;
    private Instant completedAt;
    private int attemptCount;

    /**
     * Defaults to 3, matching the library default in
     * {@code SimplyDoneProperties.Retry.maxAttempts}. Annotating it rather than leaving it
     * bare is what keeps a builder-created job from silently carrying a budget of zero.
     */
    @Builder.Default
    private int maxAttempts = 3;

    private Instant createdAt;
    private Instant updatedAt;
}
