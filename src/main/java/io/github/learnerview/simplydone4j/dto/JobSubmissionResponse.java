package io.github.learnerview.simplydone4j.dto;

import java.time.Instant;

public final class JobSubmissionResponse {
    private final String jobId;
    private final String status;
    private final String jobType;
    private final String priority;
    private final Instant scheduledAt;

    private JobSubmissionResponse(Builder builder) {
        this.jobId = builder.jobId;
        this.status = builder.status;
        this.jobType = builder.jobType;
        this.priority = builder.priority;
        this.scheduledAt = builder.scheduledAt;
    }

    public String getJobId() { return jobId; }
    public String getStatus() { return status; }
    public String getJobType() { return jobType; }
    public String getPriority() { return priority; }
    public Instant getScheduledAt() { return scheduledAt; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String jobId;
        private String status;
        private String jobType;
        private String priority;
        private Instant scheduledAt;
        private Builder() {}
        public Builder jobId(String jobId) { this.jobId = jobId; return this; }
        public Builder status(String status) { this.status = status; return this; }
        public Builder jobType(String jobType) { this.jobType = jobType; return this; }
        public Builder priority(String priority) { this.priority = priority; return this; }
        public Builder scheduledAt(Instant scheduledAt) { this.scheduledAt = scheduledAt; return this; }
        public JobSubmissionResponse build() { return new JobSubmissionResponse(this); }
    }
}
