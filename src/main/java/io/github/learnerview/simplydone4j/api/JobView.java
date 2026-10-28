package io.github.learnerview.simplydone4j.api;

import io.github.learnerview.simplydone4j.api.spi.PayloadCodec;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * Immutable, read-only view of a job in the queue.
 */
public final class JobView {

    private final String id;
    private final String jobType;
    private final String producer;
    private final String idempotencyKey;
    private final String uniqueKey;
    private final JobStatus status;
    private final JobPriority priority;
    private final Map<String, Object> payload;
    private final String result;
    private final String errorCode;
    private final Double progress;
    private final String progressMessage;
    private final boolean cancelRequested;
    private final String cancelledBy;
    private final Instant nextRunAt;
    private final Instant startedAt;
    private final Instant completedAt;
    private final int attemptCount;
    private final int maxAttempts;
    private final Integer timeoutSeconds;
    private final String callbackUrl;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final PayloadCodec codec;

    public JobView(Builder builder) {
        this.id = builder.id;
        this.jobType = builder.jobType;
        this.producer = builder.producer;
        this.idempotencyKey = builder.idempotencyKey;
        this.uniqueKey = builder.uniqueKey;
        this.status = builder.status;
        this.priority = builder.priority;
        if (builder.payload == null) {
            this.payload = Collections.emptyMap();
        } else {
            this.payload = Collections.unmodifiableMap(new java.util.LinkedHashMap<>(builder.payload));
        }
        this.result = builder.result;
        this.errorCode = builder.errorCode;
        this.progress = builder.progress != null ? builder.progress : 0.0;
        this.progressMessage = builder.progressMessage;
        this.cancelRequested = builder.cancelRequested;
        this.cancelledBy = builder.cancelledBy;
        this.nextRunAt = builder.nextRunAt;
        this.startedAt = builder.startedAt;
        this.completedAt = builder.completedAt;
        this.attemptCount = builder.attemptCount;
        this.maxAttempts = builder.maxAttempts;
        this.timeoutSeconds = builder.timeoutSeconds;
        this.callbackUrl = builder.callbackUrl;
        this.createdAt = builder.createdAt;
        this.updatedAt = builder.updatedAt;
        this.codec = builder.codec;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String id() {
        return id;
    }

    public String jobType() {
        return jobType;
    }

    public String producer() {
        return producer;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public String uniqueKey() {
        return uniqueKey;
    }

    public JobStatus status() {
        return status;
    }

    public JobPriority priority() {
        return priority;
    }

    public Map<String, Object> payload() {
        return payload;
    }

    /**
     * Deserializes the payload map into a typed representation using the configured {@link PayloadCodec}.
     */
    public <T> T payloadAs(Class<T> type) {
        if (codec == null) {
            throw new IllegalStateException("No PayloadCodec configured for this JobView");
        }
        return codec.fromMap(payload, type);
    }

    public String result() {
        return result;
    }

    public String errorCode() {
        return errorCode;
    }

    public Double progress() {
        return progress;
    }

    public String progressMessage() {
        return progressMessage;
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    public String cancelledBy() {
        return cancelledBy;
    }

    public Instant nextRunAt() {
        return nextRunAt;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant completedAt() {
        return completedAt;
    }

    public int attemptCount() {
        return attemptCount;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public Integer timeoutSeconds() {
        return timeoutSeconds;
    }

    public String callbackUrl() {
        return callbackUrl;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public static final class Builder {
        private String id;
        private String jobType;
        private String producer;
        private String idempotencyKey;
        private String uniqueKey;
        private JobStatus status;
        private JobPriority priority;
        private Map<String, Object> payload;
        private String result;
        private String errorCode;
        private Double progress;
        private String progressMessage;
        private boolean cancelRequested;
        private String cancelledBy;
        private Instant nextRunAt;
        private Instant startedAt;
        private Instant completedAt;
        private int attemptCount;
        private int maxAttempts;
        private Integer timeoutSeconds;
        private String callbackUrl;
        private Instant createdAt;
        private Instant updatedAt;
        private PayloadCodec codec;

        public Builder id(String id) { this.id = id; return this; }
        public Builder jobType(String jobType) { this.jobType = jobType; return this; }
        public Builder producer(String producer) { this.producer = producer; return this; }
        public Builder idempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; return this; }
        public Builder uniqueKey(String uniqueKey) { this.uniqueKey = uniqueKey; return this; }
        public Builder status(JobStatus status) { this.status = status; return this; }
        public Builder priority(JobPriority priority) { this.priority = priority; return this; }
        public Builder payload(Map<String, Object> payload) { this.payload = payload; return this; }
        public Builder result(String result) { this.result = result; return this; }
        public Builder errorCode(String errorCode) { this.errorCode = errorCode; return this; }
        public Builder progress(Double progress) { this.progress = progress; return this; }
        public Builder progressMessage(String progressMessage) { this.progressMessage = progressMessage; return this; }
        public Builder cancelRequested(boolean cancelRequested) { this.cancelRequested = cancelRequested; return this; }
        public Builder cancelledBy(String cancelledBy) { this.cancelledBy = cancelledBy; return this; }
        public Builder nextRunAt(Instant nextRunAt) { this.nextRunAt = nextRunAt; return this; }
        public Builder startedAt(Instant startedAt) { this.startedAt = startedAt; return this; }
        public Builder completedAt(Instant completedAt) { this.completedAt = completedAt; return this; }
        public Builder attemptCount(int attemptCount) { this.attemptCount = attemptCount; return this; }
        public Builder maxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; return this; }
        public Builder timeoutSeconds(Integer timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; return this; }
        public Builder callbackUrl(String callbackUrl) { this.callbackUrl = callbackUrl; return this; }
        public Builder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }
        public Builder updatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }
        public Builder codec(PayloadCodec codec) { this.codec = codec; return this; }

        public JobView build() {
            return new JobView(this);
        }
    }
}
