package io.github.learnerview.simplydone4j.handler;

import io.github.learnerview.simplydone4j.entity.JobEntity;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class JobContext {
    private final String jobId;
    private final String jobType;
    private final String producer;
    private final String payload;
    private final int attemptCount;
    private final int maxAttempts;
    private final int timeoutSeconds;
    private final Instant deadline;
    private final AtomicBoolean cancellationRequested;
    private final AtomicReference<ProgressCallback> progressCallback;

    private JobContext(Builder builder) {
        this.jobId = Objects.requireNonNull(builder.jobId, "jobId");
        this.jobType = Objects.requireNonNull(builder.jobType, "jobType");
        this.producer = Objects.requireNonNull(builder.producer, "producer");
        this.payload = builder.payload;
        this.attemptCount = builder.attemptCount;
        this.maxAttempts = builder.maxAttempts;
        this.timeoutSeconds = builder.timeoutSeconds;
        this.deadline = builder.deadline;
        this.cancellationRequested = builder.cancellationRequested;
        this.progressCallback = builder.progressCallback;
    }

    public static JobContext from(JobEntity job) {
        return builder()
                .jobId(job.getId())
                .jobType(job.getJobType())
                .producer(job.getProducer())
                .payload(job.getPayload())
                .attemptCount(job.getAttemptCount())
                .maxAttempts(job.getMaxAttempts())
                .timeoutSeconds(job.getTimeoutSeconds())
                .deadline(computeDeadline(job))
                .cancellationRequested(new AtomicBoolean(false))
                .progressCallback(new AtomicReference<>())
                .build();
    }

    private static Instant computeDeadline(JobEntity job) {
        if (job.getTimeoutSeconds() != null && job.getTimeoutSeconds() > 0) {
            return job.getCreatedAt() != null
                    ? job.getCreatedAt().plusSeconds(job.getTimeoutSeconds())
                    : Instant.now().plusSeconds(job.getTimeoutSeconds());
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

    public boolean isCancellationRequested() {
        return cancellationRequested != null && cancellationRequested.get();
    }

    public void requestCancellation() {
        if (cancellationRequested != null) {
            cancellationRequested.set(true);
        }
    }

    public double getProgress() {
        ProgressCallback cb = progressCallback != null ? progressCallback.get() : null;
        return cb != null ? cb.progress() : 0.0;
    }

    public void setProgress(double percent, String message) {
        ProgressCallback cb = progressCallback != null ? progressCallback.get() : null;
        if (cb != null) {
            cb.update(percent, message);
        }
    }

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
        private AtomicBoolean cancellationRequested;
        private AtomicReference<ProgressCallback> progressCallback;

        private Builder() {
            this.cancellationRequested = new AtomicBoolean(false);
            this.progressCallback = new AtomicReference<>();
        }

        public Builder jobId(String jobId) { this.jobId = jobId; return this; }
        public Builder jobType(String jobType) { this.jobType = jobType; return this; }
        public Builder producer(String producer) { this.producer = producer; return this; }
        public Builder payload(String payload) { this.payload = payload; return this; }
        public Builder attemptCount(int attemptCount) { this.attemptCount = attemptCount; return this; }
        public Builder maxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; return this; }
        public Builder timeoutSeconds(Integer timeoutSeconds) { this.timeoutSeconds = timeoutSeconds != null ? timeoutSeconds : 0; return this; }
        public Builder deadline(Instant deadline) { this.deadline = deadline; return this; }
        public Builder cancellationRequested(AtomicBoolean cancellationRequested) { this.cancellationRequested = cancellationRequested; return this; }
        public Builder progressCallback(AtomicReference<ProgressCallback> progressCallback) { this.progressCallback = progressCallback; return this; }
        public JobContext build() { return new JobContext(this); }
    }

    public interface ProgressCallback {
        void update(double percent, String message);
        double progress();
    }
}
