package io.github.learnerview.simplydone4j.handler;

import io.github.learnerview.simplydone4j.entity.JobEntity;

import java.time.Instant;
import java.util.Objects;

public final class JobContext {
    private final String jobId;
    private final String jobType;
    private final String producer;
    private final String payload;
    private final int attemptCount;
    private final int maxAttempts;
    private final int timeoutSeconds;
    private final Instant deadline;

    private JobContext(Builder builder) {
        this.jobId = Objects.requireNonNull(builder.jobId, "jobId");
        this.jobType = Objects.requireNonNull(builder.jobType, "jobType");
        this.producer = Objects.requireNonNull(builder.producer, "producer");
        this.payload = builder.payload;
        this.attemptCount = builder.attemptCount;
        this.maxAttempts = builder.maxAttempts;
        this.timeoutSeconds = builder.timeoutSeconds;
        this.deadline = builder.deadline;
    }

    public static JobContext from(JobEntity job) {
        return from(job, 0);
    }

    /**
     * Like {@link #from(JobEntity)}, but falls back to {@code defaultTimeoutSeconds} when the
     * job does not carry its own per-job timeout, mirroring the executor, so a handler can
     * see the deadline that the watchdog will actually enforce.
     */
    public static JobContext from(JobEntity job, int defaultTimeoutSeconds) {
        Instant startedAt = job.getStartedAt();
        return from(job, defaultTimeoutSeconds, startedAt);
    }

    public static JobContext from(JobEntity job, int defaultTimeoutSeconds, Instant startedAt) {
        int timeoutSeconds = job.getTimeoutSeconds() != null && job.getTimeoutSeconds() > 0
                ? job.getTimeoutSeconds() : defaultTimeoutSeconds;
        return builder()
                .jobId(job.getId())
                .jobType(job.getJobType())
                .producer(job.getProducer())
                .payload(job.getPayload())
                .attemptCount(job.getAttemptCount())
                .maxAttempts(job.getMaxAttempts())
                .timeoutSeconds(timeoutSeconds)
                .deadline(computeDeadline(startedAt, timeoutSeconds))
                .build();
    }

    private static Instant computeDeadline(Instant startedAt, int timeoutSeconds) {
        if (timeoutSeconds > 0) {
            Instant base = startedAt != null ? startedAt : Instant.now();
            return base.plusSeconds(timeoutSeconds);
        }
        return null;
    }

    public String getJobId() { return jobId; }
    public String getJobType() { return jobType; }
    public String getProducer() { return producer; }
    public String getPayload() { return payload; }
    public int getAttemptCount() { return attemptCount; }
    public int getMaxAttempts() { return maxAttempts; }
    public int getTimeoutSeconds() { return timeoutSeconds; }

    public Instant getDeadline() { return deadline; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String jobId;
        private String jobType;
        private String producer;
        private String payload;
        private int attemptCount;
        private int maxAttempts;
        private int timeoutSeconds;
        private Instant deadline;
        private Builder() {}

        public Builder jobId(String jobId) { this.jobId = jobId; return this; }
        public Builder jobType(String jobType) { this.jobType = jobType; return this; }
        public Builder producer(String producer) { this.producer = producer; return this; }
        public Builder payload(String payload) { this.payload = payload; return this; }
        public Builder attemptCount(int attemptCount) { this.attemptCount = attemptCount; return this; }
        public Builder maxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; return this; }
        public Builder timeoutSeconds(Integer timeoutSeconds) { this.timeoutSeconds = timeoutSeconds != null ? timeoutSeconds : 0; return this; }
        public Builder deadline(Instant deadline) { this.deadline = deadline; return this; }
        public JobContext build() { return new JobContext(this); }
    }
}
