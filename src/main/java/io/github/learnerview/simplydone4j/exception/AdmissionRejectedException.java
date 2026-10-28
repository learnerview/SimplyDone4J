package io.github.learnerview.simplydone4j.exception;

import java.time.Duration;

/**
 * The submission was refused by the application's {@code AdmissionPolicy}.
 *
 * <p>This is the application's own decision, reported back in the library's own type so
 * a caller can tell "the queue is full" ({@link QueueFullException}) from "the policy
 * said no" without catching a class from somebody else's library.</p>
 */
public final class AdmissionRejectedException extends RuntimeException {

    private final Duration retryAfter;

    public AdmissionRejectedException(String reason, Duration retryAfter) {
        super(reason == null || reason.isBlank() ? "Submission rejected by the admission policy" : reason);
        this.retryAfter = retryAfter;
    }

    /**
     * How long the caller should wait before retrying, or {@code null} when the policy
     * did not say.
     */
    public Duration getRetryAfter() {
        return retryAfter;
    }
}
