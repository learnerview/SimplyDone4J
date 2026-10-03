package io.github.learnerview.simplydone4j.handler;

import io.github.learnerview.simplydone4j.entity.JobEntity;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Everything a handler is given about the job it is running.
 *
 * <p>Mostly immutable, with the two exception fields deliberately atomic rather than
 * plain. A handler reports progress from its own worker thread while the engine polls
 * {@code cancellationRequested} from the watchdog and monitoring threads, so those two
 * need to be safe to touch concurrently.</p>
 *
 * <p>The three identity fields are required at construction. A context without a job id
 * cannot be logged, correlated, or retried, so failing loudly here beats handing a
 * handler a context that breaks somewhere less obvious later.</p>
 */
@Getter
@Builder
public class JobContext {
    private final String jobId;
    private final String jobType;
    private final String producer;
    private final String payload;
    private final int attemptCount;
    private final int maxAttempts;
    private final int timeoutSeconds;

    /**
     * Wall-clock point after which this job should be considered overdue, or
     * {@code null} when no timeout applies. Derived from the job's own {@code createdAt}
     * rather than from when execution started, so a job that sat queued for a while is
     * still measured against its real deadline.
     */
    private final Instant deadline;

    private final AtomicBoolean cancellationRequested;
    private final AtomicReference<ProgressCallback> progressCallback;

    public static JobContext from(JobEntity job) {
        Integer jobTimeout = job.getTimeoutSeconds();
        return builder()
                .jobId(Objects.requireNonNull(job.getId(), "jobId"))
                .jobType(Objects.requireNonNull(job.getJobType(), "jobType"))
                .producer(Objects.requireNonNull(job.getProducer(), "producer"))
                .payload(job.getPayload())
                .attemptCount(job.getAttemptCount())
                .maxAttempts(job.getMaxAttempts())
                // A job with no explicit timeout reports zero here rather than null: the
                // field is a primitive, and the executor's default is already applied to the
                // watchdog, so the handler only ever reads this for reporting.
                .timeoutSeconds(jobTimeout != null ? jobTimeout : 0)
                .deadline(computeDeadline(job))
                .cancellationRequested(new AtomicBoolean(false))
                .progressCallback(new AtomicReference<>())
                .build();
    }

    private static Instant computeDeadline(JobEntity job) {
        if (job.getTimeoutSeconds() != null && job.getTimeoutSeconds() > 0) {
            return job.getCreatedAt() != null
                    ? job.getCreatedAt().plusSeconds(job.getTimeoutSeconds())
                    : Instant.now().plusSeconds(job.getTimeoutSeconds());
        }
        return null;
    }

    /**
     * Null-tolerant even though {@link #from} always sets the field, so a hand-built
     * context from {@code builder()} reads as "not cancelled" instead of throwing. A
     * handler polling this should not have to guard the guard.
     */
    public boolean isCancellationRequested() {
        return cancellationRequested != null && cancellationRequested.get();
    }

    public void requestCancellation() {
        if (cancellationRequested != null) {
            cancellationRequested.set(true);
        }
    }

    /** Last reported progress, or {@code 0.0} if the handler has never reported any. */
    public double getProgress() {
        ProgressCallback cb = progressCallback != null ? progressCallback.get() : null;
        return cb != null ? cb.progress() : 0.0;
    }

    public void setProgress(double percent, String message) {
        ProgressCallback cb = progressCallback != null ? progressCallback.get() : null;
        if (cb != null) {
            cb.update(percent, message);
        }
    }

    /** How a handler reports in-flight progress. */
    public interface ProgressCallback {
        void update(double percent, String message);

        double progress();
    }
}
