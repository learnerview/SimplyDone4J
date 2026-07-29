package io.github.learnerview.simplydone4j.model;

public enum JobStatus {
    QUEUED,
    RUNNING,
    RETRY_SCHEDULED,
    SUCCESS,
    FAILED,
    CANCELLED,
    DLQ
}
