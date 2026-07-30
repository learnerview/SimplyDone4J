package io.github.learnerview.simplydone4j.dto;

import java.time.Instant;
import java.util.Map;

public final class JobResponse {
    private final String id;
    private final String jobType;
    private final String producer;
    private final String idempotencyKey;
    private final String status;
    private final String priority;
    private final Map<String, Object> payload;
    private final String result;
    private final Instant nextRunAt;
    private final Instant visibleAt;
    private final String leaseOwner;
    private final Integer timeoutSeconds;
    private final String callbackUrl;
    private final Instant startedAt;
    private final Instant completedAt;
    private final int attemptCount;
    private final int maxAttempts;
    private final Instant createdAt;
    private final Instant updatedAt;

    private JobResponse(Builder builder) {
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
    public String getJobType() { return jobType; }
    public String getProducer() { return producer; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getStatus() { return status; }
    public String getPriority() { return priority; }
    public Map<String, Object> getPayload() { return payload; }
    public String getResult() { return result; }
    public Instant getNextRunAt() { return nextRunAt; }
    public Instant getVisibleAt() { return visibleAt; }
    public String getLeaseOwner() { return leaseOwner; }
    public Integer getTimeoutSeconds() { return timeoutSeconds; }
    public String getCallbackUrl() { return callbackUrl; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public int getAttemptCount() { return attemptCount; }
    public int getMaxAttempts() { return maxAttempts; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String id;
        private String jobType;
        private String producer;
        private String idempotencyKey;
        private String status;
        private String priority;
        private Map<String, Object> payload;
        private String result;
        private Instant nextRunAt;
        private Instant visibleAt;
        private String leaseOwner;
        private Integer timeoutSeconds;
        private String callbackUrl;
        private Instant startedAt;
        private Instant completedAt;
        private int attemptCount;
        private int maxAttempts;
        private Instant createdAt;
        private Instant updatedAt;
        private Builder() {}
        public Builder id(String id) { this.id = id; return this; }
        public Builder jobType(String jobType) { this.jobType = jobType; return this; }
        public Builder producer(String producer) { this.producer = producer; return this; }
        public Builder idempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; return this; }
        public Builder status(String status) { this.status = status; return this; }
        public Builder priority(String priority) { this.priority = priority; return this; }
        public Builder payload(Map<String, Object> payload) { this.payload = payload; return this; }
        public Builder result(String result) { this.result = result; return this; }
        public Builder nextRunAt(Instant nextRunAt) { this.nextRunAt = nextRunAt; return this; }
        public Builder visibleAt(Instant visibleAt) { this.visibleAt = visibleAt; return this; }
        public Builder leaseOwner(String leaseOwner) { this.leaseOwner = leaseOwner; return this; }
        public Builder timeoutSeconds(Integer timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; return this; }
        public Builder callbackUrl(String callbackUrl) { this.callbackUrl = callbackUrl; return this; }
        public Builder startedAt(Instant startedAt) { this.startedAt = startedAt; return this; }
        public Builder completedAt(Instant completedAt) { this.completedAt = completedAt; return this; }
        public Builder attemptCount(int attemptCount) { this.attemptCount = attemptCount; return this; }
        public Builder maxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; return this; }
        public Builder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }
        public Builder updatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }
        public JobResponse build() { return new JobResponse(this); }
    }
}
