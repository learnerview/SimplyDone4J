package io.github.learnerview.simplydone4j.exception;

/**
 * Raised when a job is submitted for a job type that no handler is registered for.
 *
 * <p>Distinct from a generic failure because the condition is permanent: no amount of
 * retrying will produce a handler. The executor therefore fails such a job terminally
 * instead of spending its attempt budget.</p>
 */
public class UnknownJobTypeException extends IllegalArgumentException {

    public UnknownJobTypeException(String jobType) {
        super("No handler registered for jobType: " + jobType);
    }
}
