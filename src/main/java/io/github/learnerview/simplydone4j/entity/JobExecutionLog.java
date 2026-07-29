package io.github.learnerview.simplydone4j.entity;

import java.time.Instant;

public final class JobExecutionLog {
    private String id;
    private String jobId;
    private int attempt;
    private String status;
    private String message;
    private Long durationMs;
    private Instant executedAt;

    public JobExecutionLog() {}

    private JobExecutionLog(Builder builder) {
        this.id = builder.id;
        this.jobId = builder.jobId;
        this.attempt = builder.attempt;
        this.status = builder.status;
        this.message = builder.message;
        this.durationMs = builder.durationMs;
        this.executedAt = builder.executedAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }
    public int getAttempt() { return attempt; }
    public void setAttempt(int attempt) { this.attempt = attempt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public Instant getExecutedAt() { return executedAt; }
    public void setExecutedAt(Instant executedAt) { this.executedAt = executedAt; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String id;
        private String jobId;
        private int attempt;
        private String status;
        private String message;
        private Long durationMs;
        private Instant executedAt;
        private Builder() {}
        public Builder id(String id) { this.id = id; return this; }
        public Builder jobId(String jobId) { this.jobId = jobId; return this; }
        public Builder attempt(int attempt) { this.attempt = attempt; return this; }
        public Builder status(String status) { this.status = status; return this; }
        public Builder message(String message) { this.message = message; return this; }
        public Builder durationMs(Long durationMs) { this.durationMs = durationMs; return this; }
        public Builder executedAt(Instant executedAt) { this.executedAt = executedAt; return this; }
        public JobExecutionLog build() { return new JobExecutionLog(this); }
    }
}
