package io.github.learnerview.simplydone4j.api;

import io.github.learnerview.simplydone4j.model.CancelOutcome;
import io.github.learnerview.simplydone4j.model.JobStatus;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Primary programmatic client for interacting with SimplyDone4J.
 *
 * <p>Supports submitting jobs with typed payloads, querying job status,
 * cooperative cancellation, and dead-letter/failed retry.</p>
 */
public interface JobClient {

    /**
     * Submits a job with a configured {@link JobSubmission}.
     *
     * @param submission the submission specification
     * @return a {@link JobHandle} representing the admitted job
     */
    JobHandle submit(JobSubmission submission);

    /**
     * Submits a job with default options.
     *
     * @param jobType the type of job
     * @param payload the typed payload
     * @return a {@link JobHandle}
     */
    default JobHandle submit(String jobType, Object payload) {
        return submit(JobSubmission.of(jobType, payload).build());
    }

    /**
     * Submits a job with customized options via a builder callback.
     *
     * @param jobType the type of job
     * @param payload the typed payload
     * @param customizer consumer to configure priority, delay, timeout, etc.
     * @return a {@link JobHandle}
     */
    default JobHandle submit(String jobType, Object payload, Consumer<JobSubmission.Builder> customizer) {
        JobSubmission.Builder builder = JobSubmission.of(jobType, payload);
        if (customizer != null) {
            customizer.accept(builder);
        }
        return submit(builder.build());
    }

    /**
     * Retrieves a read-only snapshot of a job.
     *
     * @param jobId the unique job id
     * @return an {@link Optional} containing the {@link JobView} if found
     */
    Optional<JobView> get(String jobId);

    /**
     * Requests cancellation of a job.
     *
     * <ul>
     *   <li>If the job is queued, it is immediately transitioned to {@code CANCELLED} and removed from the queue.</li>
     *   <li>If the job is currently running, a cancellation flag is raised for the worker to stop execution.</li>
     * </ul>
     *
     * @param jobId the job to cancel
     * @return the {@link CancelOutcome}
     */
    CancelOutcome cancel(String jobId);

    /**
     * Manually re-drives a job that is parked in {@code DLQ} or {@code FAILED}.
     *
     * @param jobId the job id
     * @return {@code true} if the job was requeued, {@code false} if not found or cannot be requeued
     */
    boolean retry(String jobId);

    /**
     * Lists jobs currently in the specified status, newest first.
     *
     * @param status the status to query
     * @return list of matching {@link JobView}s
     */
    List<JobView> listByStatus(JobStatus status);

    /**
     * Counts how many jobs are currently in the specified status.
     *
     * @param status the status to query
     * @return count of matching jobs
     */
    long countByStatus(JobStatus status);
}
