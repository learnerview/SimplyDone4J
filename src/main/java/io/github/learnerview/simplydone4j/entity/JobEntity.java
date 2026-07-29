package io.github.learnerview.simplydone4j.entity;

import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;

import java.time.Instant;
import java.util.Objects;

public final class JobEntity {
    private String id;
    private String jobType;
    private String producer;
    private String idempotencyKey;
    private JobStatus status;
    private JobPriority priority;
    private String payload;
    private String result;
    private Instant nextRunAt;
    private Instant visibleAt;
    private String leaseOwner;
    private String leaseToken;
    private String executionType;
    private String executionEndpoint;
    private Integer timeoutSeconds;
    private String callbackUrl;
    private Instant startedAt;
    private Instant completedAt;
    private int attemptCount;
    private int maxAttempts;
    private Instant createdAt;
    private Instant updatedAt;

    public JobEntity() {}

    private JobEntity(Builder builder) {
        this.id = builder.id;
        this.jobType = builder.jobType;
        this.producer = builder.producer;
        this.idempotencyKey = builder.idempotencyKey;
        this.status = builder.status;
        this.priority = builder.priority;
        this.payload = builder.payload;
        this.result = builder.result;
        this.nextRunAt = builder.nextRunAt;
        this.visibleAt = builder.visibleAt;
        this.leaseOwner = builder.leaseOwner;
        this.leaseToken = builder.leaseToken;
        this.executionType = builder.executionType;
        this.executionEndpoint = builder.executionEndpoint;
        this.timeoutSeconds = builder.timeoutSeconds;
        this.callbackUrl = builder.callbackUrl;
        this.startedAt = builder.startedAt;
        this.completedAt = builder.completedAt;
        this.attemptCount = builder.attemptCount;
        this.maxAttempts = builder.maxAttempts;
        this.createdAt = builder.createdAt;
        this.updatedAt = builder.updatedAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getJobType() { return jobType; }
    public void setJobType(String jobType) { this.jobType = jobType; }
    public String getProducer() { return producer; }
    public void setProducer(String producer) { this.producer = producer; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus status) { this.status = status; }
    public JobPriority getPriority() { return priority; }
    public void setPriority(JobPriority priority) { this.priority = priority; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public Instant getNextRunAt() { return nextRunAt; }
    public void setNextRunAt(Instant nextRunAt) { this.nextRunAt = nextRunAt; }
    public Instant getVisibleAt() { return visibleAt; }
    public void setVisibleAt(Instant visibleAt) { this.visibleAt = visibleAt; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String leaseOwner) { this.leaseOwner = leaseOwner; }
    public String getLeaseToken() { return leaseToken; }
    public void setLeaseToken(String leaseToken) { this.leaseToken = leaseToken; }
    public String getExecutionType() { return executionType; }
    public void setExecutionType(String executionType) { this.executionType = executionType; }
    public String getExecutionEndpoint() { return executionEndpoint; }
    public void setExecutionEndpoint(String executionEndpoint) { this.executionEndpoint = executionEndpoint; }
    public Integer getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(Integer timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public String getCallbackUrl() { return callbackUrl; }
    public void setCallbackUrl(String callbackUrl) { this.callbackUrl = callbackUrl; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String id;
        private String jobType;
        private String producer;
        private String idempotencyKey;
        private JobStatus status;
        private JobPriority priority;
        private String payload;
        private String result;
        private Instant nextRunAt;
        private Instant visibleAt;
        private String leaseOwner;
        private String leaseToken;
        private String executionType;
        private String executionEndpoint;
        private Integer timeoutSeconds;
        private String callbackUrl;
        private Instant startedAt;
        private Instant completedAt;
        private int attemptCount;
        private int maxAttempts = 3;
        private Instant createdAt;
        private Instant updatedAt;

        private Builder() {}

        public Builder id(String id) { this.id = id; return this; }
        public Builder jobType(String jobType) { this.jobType = jobType; return this; }
        public Builder producer(String producer) { this.producer = producer; return this; }
        public Builder idempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; return this; }
        public Builder status(JobStatus status) { this.status = status; return this; }
        public Builder priority(JobPriority priority) { this.priority = priority; return this; }
        public Builder payload(String payload) { this.payload = payload; return this; }
        public Builder result(String result) { this.result = result; return this; }
        public Builder nextRunAt(Instant nextRunAt) { this.nextRunAt = nextRunAt; return this; }
        public Builder visibleAt(Instant visibleAt) { this.visibleAt = visibleAt; return this; }
        public Builder leaseOwner(String leaseOwner) { this.leaseOwner = leaseOwner; return this; }
        public Builder leaseToken(String leaseToken) { this.leaseToken = leaseToken; return this; }
        public Builder executionType(String executionType) { this.executionType = executionType; return this; }
        public Builder executionEndpoint(String executionEndpoint) { this.executionEndpoint = executionEndpoint; return this; }
        public Builder timeoutSeconds(Integer timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; return this; }
        public Builder callbackUrl(String callbackUrl) { this.callbackUrl = callbackUrl; return this; }
        public Builder startedAt(Instant startedAt) { this.startedAt = startedAt; return this; }
        public Builder completedAt(Instant completedAt) { this.completedAt = completedAt; return this; }
        public Builder attemptCount(int attemptCount) { this.attemptCount = attemptCount; return this; }
        public Builder maxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; return this; }
        public Builder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }
        public Builder updatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }
        public JobEntity build() { return new JobEntity(this); }
    }
}
