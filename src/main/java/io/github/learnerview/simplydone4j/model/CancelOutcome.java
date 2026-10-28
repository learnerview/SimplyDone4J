package io.github.learnerview.simplydone4j.model;

/**
 * What a cancellation request did.
 *
 * <p>Cancelling a job is not one operation but two, because a job that is already
 * running cannot be un-run: the worker that owns it has to stop. The caller has to be
 * able to tell the two apart, or it would report a cancelled job that is still
 * executing.</p>
 */
public enum CancelOutcome {

    /**
     * The job was waiting and is now terminally cancelled. It will never run.
     */
    CANCELLED,

    /**
     * The job was running. The request was recorded and the worker has been told to
     * stop; the job reaches its terminal {@code CANCELLED} state when the handler
     * returns.
     */
    REQUESTED,

    /**
     * The job is in a state that cannot be cancelled, such as {@code SUCCESS} or
     * {@code DLQ}.
     */
    NOT_CANCELLABLE,

    /**
     * No such job.
     */
    NOT_FOUND
}
