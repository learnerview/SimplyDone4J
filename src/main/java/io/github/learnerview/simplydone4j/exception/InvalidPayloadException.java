package io.github.learnerview.simplydone4j.exception;

/**
 * The payload could not be read.
 *
 * <p>Thrown when a payload is submitted in a form that cannot be serialised, and available
 * for handlers that read the payload themselves: a handler that knows the payload is
 * malformed can throw this, and the engine records the job as terminally failed with
 * {@link io.github.learnerview.simplydone4j.model.JobErrorCode#PAYLOAD_INVALID} instead of
 * burning the retry budget on a payload that will not read any better next time.</p>
 */
public class InvalidPayloadException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public InvalidPayloadException(String message) {
        super(message);
    }

    public InvalidPayloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
