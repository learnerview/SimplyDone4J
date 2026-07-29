package io.github.learnerview.simplydone4j.handler;

import io.github.learnerview.simplydone4j.entity.JobEntity;

import java.util.Objects;

public final class JobContext {
    private final String jobId;
    private final String jobType;
    private final String producer;
    private final String payload;
    private final int attemptCount;
    private final int maxAttempts;

    public JobContext(String jobId, String jobType, String producer,
                      String payload, int attemptCount, int maxAttempts) {
        this.jobId = Objects.requireNonNull(jobId, "jobId");
        this.jobType = Objects.requireNonNull(jobType, "jobType");
        this.producer = Objects.requireNonNull(producer, "producer");
        this.payload = payload;
        this.attemptCount = attemptCount;
        this.maxAttempts = maxAttempts;
    }

    public static JobContext from(JobEntity job) {
        return new JobContext(job.getId(), job.getJobType(), job.getProducer(),
                job.getPayload(), job.getAttemptCount(), job.getMaxAttempts());
    }

    public String getJobId() { return jobId; }
    public String getJobType() { return jobType; }
    public String getProducer() { return producer; }
    public String getPayload() { return payload; }
    public int getAttemptCount() { return attemptCount; }
    public int getMaxAttempts() { return maxAttempts; }
}
