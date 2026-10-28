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
     * Why the job failed, as a stable code from {@link io.github.learnerview.simplydone4j.model.JobErrorCode}.
     *
     * <p>Null while the job has not failed. The free-text {@code result} explains the
     * failure to a human; this field is what a program branches on.</p>
     */
    private String errorCode;

    /**
     * How far the handler got, as a percentage, and the message it last reported.
     *
     * <p>Written by the handler while it runs, throttled by the executor, and cleared on a
     * fresh attempt so a retried job does not report the previous attempt's progress.</p>
     */
    private Double progress;
    private String progressMessage;

    /**
     * Set when somebody asked a running job to stop.
     *
     * <p>A running job cannot be un-run, so a cancellation of one is a request rather than
     * a transition: the flag is written, the worker notices it and stops the handler, and
     * the worker then writes the terminal {@code CANCELLED} state. The flag survives a
     * reaped attempt on purpose: a job somebody asked to stop should not come back.</p>
     */
    private Boolean cancelRequested;

    /** Who asked for the cancellation, for the audit trail. */
    private String cancelledBy;

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
