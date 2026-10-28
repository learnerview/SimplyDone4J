package io.github.learnerview.simplydone4j.model;

/**
 * Why a job failed.
 *
 * <p>The status says whether the job failed; the error code says why. Without it every
 * failure looks the same -- a timeout, a handler that threw, a payload the handler could
 * not read and a job type nobody handles are all just "FAILED", and the only way to tell
 * them apart is to read the free-text message. That distinction decides what an operator
 * does next: retry the timeout with a longer budget, fix the handler, fix the producer,
 * or register the missing handler.</p>
 *
 * <p>These are stable, machine-readable values. The message beside them is not.</p>
 */
public enum JobErrorCode {

    /** No handler is registered for the job's type, and none ever will be. */
    UNKNOWN_JOB_TYPE,

    /** The payload could not be read by the handler, or was not valid to begin with. */
    PAYLOAD_INVALID,

    /** The handler threw an exception. */
    HANDLER_THREW,

    /** The handler did not finish within the job's timeout. */
    TIMEOUT,

    /** The lease was lost while the job was running: another worker took it over. */
    LEASE_LOST
}
