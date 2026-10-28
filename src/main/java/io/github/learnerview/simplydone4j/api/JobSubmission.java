package io.github.learnerview.simplydone4j.api;

import io.github.learnerview.simplydone4j.model.JobPriority;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable specification for submitting a job.
 *
 * <p>Construct using the fluent builder:</p>
 * <pre>{@code
 * JobSubmission submission = JobSubmission.builder("email.send")
 *     .payload(new EmailPayload("user@example.com", "Welcome!"))
 *     .priority(JobPriority.HIGH)
 *     .idempotencyKey("welcome:" + userId)
 *     .delay(Duration.ofMinutes(5))
 *     .maxAttempts(3)
 *     .timeout(Duration.ofSeconds(60))
 *     .build();
 * }</pre>
 */
public final class JobSubmission {

    private final String jobType;
    private final Object payload;
    private final String idempotencyKey;
    private final String uniqueKey;
    private final JobPriority priority;
    private final Instant runAt;
    private final Integer maxAttempts;
    private final Integer timeoutSeconds;
    private final String callbackUrl;
    private final String producer;

    private JobSubmission(Builder builder) {
        this.jobType = Objects.requireNonNull(builder.jobType, "jobType must not be null");
        if (this.jobType.isBlank()) {
            throw new IllegalArgumentException("jobType must not be blank");
        }
        this.payload = builder.payload;
        this.idempotencyKey = (builder.idempotencyKey != null && !builder.idempotencyKey.isBlank())
                ? builder.idempotencyKey
                : UUID.randomUUID().toString();
        this.uniqueKey = builder.uniqueKey;
        this.priority = builder.priority != null ? builder.priority : JobPriority.NORMAL;
        this.runAt = builder.runAt;
        this.maxAttempts = builder.maxAttempts;
        this.timeoutSeconds = builder.timeoutSeconds;
        this.callbackUrl = builder.callbackUrl;
        this.producer = (builder.producer != null && !builder.producer.isBlank())
                ? builder.producer
                : "default";
    }

    public static Builder builder(String jobType) {
        return new Builder(jobType);
    }

    public static Builder of(String jobType, Object payload) {
        return new Builder(jobType).payload(payload);
    }

    public String getJobType() {
        return jobType;
    }

    public Object getPayload() {
        return payload;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getUniqueKey() {
        return uniqueKey;
    }

    public JobPriority getPriority() {
        return priority;
    }

    public Instant getRunAt() {
        return runAt;
    }

    public Integer getMaxAttempts() {
        return maxAttempts;
    }

    public Integer getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public String getCallbackUrl() {
        return callbackUrl;
    }

    public String getProducer() {
        return producer;
    }

    public static final class Builder {
        private final String jobType;
        private Object payload;
        private String idempotencyKey;
        private String uniqueKey;
        private JobPriority priority = JobPriority.NORMAL;
        private Instant runAt;
        private Integer maxAttempts;
        private Integer timeoutSeconds;
        private String callbackUrl;
        private String producer = "default";

        private Builder(String jobType) {
            this.jobType = Objects.requireNonNull(jobType, "jobType must not be null");
        }

        public Builder payload(Object payload) {
            this.payload = payload;
            return this;
        }

        public Builder idempotencyKey(String idempotencyKey) {
            this.idempotencyKey = idempotencyKey;
            return this;
        }

        public Builder uniqueKey(String uniqueKey) {
            this.uniqueKey = uniqueKey;
            return this;
        }

        public Builder priority(JobPriority priority) {
            this.priority = priority;
            return this;
        }

        public Builder runAt(Instant runAt) {
            this.runAt = runAt;
            return this;
        }

        public Builder delay(Duration delay) {
            if (delay != null && !delay.isNegative()) {
                this.runAt = Instant.now().plus(delay);
            }
            return this;
        }

        public Builder maxAttempts(int maxAttempts) {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be >= 1");
            }
            this.maxAttempts = maxAttempts;
            return this;
        }

        public Builder timeout(Duration timeout) {
            if (timeout != null) {
                this.timeoutSeconds = (int) Math.max(1, timeout.toSeconds());
            }
            return this;
        }

        public Builder timeoutSeconds(int timeoutSeconds) {
            if (timeoutSeconds < 1) {
                throw new IllegalArgumentException("timeoutSeconds must be >= 1");
            }
            this.timeoutSeconds = timeoutSeconds;
            return this;
        }

        public Builder callbackUrl(String callbackUrl) {
            this.callbackUrl = callbackUrl;
            return this;
        }

        public Builder producer(String producer) {
            this.producer = producer;
            return this;
        }

        public JobSubmission build() {
            return new JobSubmission(this);
        }
    }
}
