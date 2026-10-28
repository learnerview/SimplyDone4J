package io.github.learnerview.simplydone4j.event;

public enum JobEvent {
    JOB_CREATED,
    /**
     * The job became runnable again: a retry was promoted, or a dead letter was
     * requeued. Distinct from {@link #JOB_CREATED}, which reports the first submission.
     */
    JOB_QUEUED,
    JOB_STARTED,
    JOB_COMPLETED,
    JOB_FAILED,
    JOB_RETRY,
    JOB_CANCELLED
}
