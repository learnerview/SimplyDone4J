package io.github.learnerview.simplydone4j.exception;

import java.time.Duration;

/**
 * A submission with the same idempotency key is still being persisted or enqueued.
 *
 * <p>The key is reserved before the job is written, so a duplicate cannot be handed a job
 * id that the original submission may still roll back. Callers should retry after the
 * suggested delay; the retry will either return the committed job or take over a
 * reservation whose original submission did not finish.</p>
 */
public final class JobSubmissionInProgressException extends RuntimeException {

    private final Duration retryAfter;

    public JobSubmissionInProgressException(String idempotencyKey, Duration retryAfter) {
        super("A submission with idempotency key '" + idempotencyKey
                + "' is still in progress; retry shortly");
        this.retryAfter = retryAfter;
    }

    /**
     * How long the caller should wait before retrying.
     */
    public Duration getRetryAfter() {
        return retryAfter;
    }
}
