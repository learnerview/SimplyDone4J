package io.github.learnerview.simplydone4j.api;

/**
 * Handle returned when a job is successfully submitted to the queue.
 *
 * @param id the unique identifier assigned to the job (UUIDv7)
 * @param jobType the type of work to perform
 * @param status the initial status of the job (typically QUEUED)
 */
public record JobHandle(String id, String jobType, String status) {

    public JobHandle {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (jobType == null || jobType.isBlank()) {
            throw new IllegalArgumentException("jobType must not be blank");
        }
    }
}
