package io.github.learnerview.simplydone4j.event;

import io.github.learnerview.simplydone4j.entity.JobEntity;

import java.time.Instant;
import java.util.Map;

public final class JobEventData {
    private final String jobId;
    private final String jobType;
    private final String producer;
    private final String status;
    private final String priority;
    private final String result;
    private final Integer attempt;
    private final Integer maxAttempts;
    private final Long durationMs;
    private final Instant timestamp;
    private final Map<String, Object> additionalData;

    private JobEventData(Builder builder) {
        this.jobId = builder.jobId;
        this.jobType = builder.jobType;
        this.producer = builder.producer;
        this.status = builder.status;
        this.priority = builder.priority;
        this.result = builder.result;
        this.attempt = builder.attempt;
        this.maxAttempts = builder.maxAttempts;
        this.durationMs = builder.durationMs;
        this.timestamp = builder.timestamp;
        this.additionalData = builder.additionalData;
    }

    public String getJobId() { return jobId; }
    public String getJobType() { return jobType; }
    public String getProducer() { return producer; }
    public String getStatus() { return status; }
    public String getPriority() { return priority; }
    public String getResult() { return result; }
    public Integer getAttempt() { return attempt; }
    public Integer getMaxAttempts() { return maxAttempts; }
    public Long getDurationMs() { return durationMs; }
    public Instant getTimestamp() { return timestamp; }
    public Map<String, Object> getAdditionalData() { return additionalData; }

    public static JobEventData from(JobEntity job) {
        return builder()
                .jobId(job.getId())
                .jobType(job.getJobType())
                .producer(job.getProducer())
                .status(job.getStatus().name())
                .priority(job.getPriority().name())
                .result(job.getResult())
                .timestamp(Instant.now())
                .build();
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String jobId;
        private String jobType;
        private String producer;
        private String status;
        private String priority;
        private String result;
        private Integer attempt;
        private Integer maxAttempts;
        private Long durationMs;
        private Instant timestamp;
        private Map<String, Object> additionalData;
        private Builder() {}
        public Builder jobId(String v) { this.jobId = v; return this; }
        public Builder jobType(String v) { this.jobType = v; return this; }
        public Builder producer(String v) { this.producer = v; return this; }
        public Builder status(String v) { this.status = v; return this; }
        public Builder priority(String v) { this.priority = v; return this; }
        public Builder result(String v) { this.result = v; return this; }
        public Builder attempt(Integer v) { this.attempt = v; return this; }
        public Builder maxAttempts(Integer v) { this.maxAttempts = v; return this; }
        public Builder durationMs(Long v) { this.durationMs = v; return this; }
        public Builder timestamp(Instant v) { this.timestamp = v; return this; }
        public Builder additionalData(Map<String, Object> v) { this.additionalData = v; return this; }
        public JobEventData build() { return new JobEventData(this); }
    }
}
