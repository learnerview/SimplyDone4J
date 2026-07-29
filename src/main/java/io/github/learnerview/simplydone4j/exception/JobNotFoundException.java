package io.github.learnerview.simplydone4j.exception;

public final class JobNotFoundException extends RuntimeException {
    public JobNotFoundException(String jobId) {
        super("Job not found: " + jobId);
    }
}
